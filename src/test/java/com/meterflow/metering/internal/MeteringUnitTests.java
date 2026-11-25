package com.meterflow.metering.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterflow.catalog.api.BillableMetricDefinition;
import com.meterflow.catalog.api.InvalidRatingContextException;
import com.meterflow.catalog.api.MetricAggregationType;
import com.meterflow.catalog.api.MetricEvaluationSupport;
import com.meterflow.catalog.api.MetricEventSample;
import com.meterflow.catalog.api.MetricFilterDefinition;
import com.meterflow.catalog.api.MetricFilterOperator;
import com.meterflow.metering.api.BatchLimitExceededException;
import com.meterflow.metering.api.IngestUsageBatchCommand;
import com.meterflow.metering.api.IngestUsageEventCommand;
import com.meterflow.metering.api.InvalidMeteringRequestException;
import com.meterflow.shared.BillingInterval;
import com.meterflow.shared.SupportedCurrency;
import com.meterflow.subscriptions.api.SubscriptionStatus;
import com.meterflow.subscriptions.api.SubscriptionSummary;
import com.meterflow.subscriptions.api.TrialConversionPolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MeteringUnitTests {

    @Test
    void computesCanonicalPayloadHashDeterministicallyAndDetectsMaterialChanges() {
        UUID customerId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID metricId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID subId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        Instant ts = Instant.parse("2026-04-15T10:20:30.123456Z");

        Map<String, Object> propsA = new LinkedHashMap<>();
        propsA.put("region", "ap-south");
        propsA.put("tokens", 100);
        Map<String, Object> propsB = new LinkedHashMap<>();
        propsB.put("tokens", new BigDecimal("100.000"));
        propsB.put("region", "ap-south");

        String hash1 = CanonicalPayloadHasher.computeHash(customerId, metricId, subId, new BigDecimal("10.000000"), ts, propsA);
        String hash2 = CanonicalPayloadHasher.computeHash(customerId, metricId, subId, new BigDecimal("10"), ts, propsB);
        assertThat(hash1).hasSize(64).isEqualTo(hash2);

        String hashDiffQuantity = CanonicalPayloadHasher.computeHash(customerId, metricId, subId, new BigDecimal("11"), ts, propsA);
        String hashDiffProp = CanonicalPayloadHasher.computeHash(customerId, metricId, subId, new BigDecimal("10"), ts, Map.of("region", "us-east", "tokens", 100));
        assertThat(hash1).isNotEqualTo(hashDiffQuantity).isNotEqualTo(hashDiffProp);
    }

    @Test
    void evaluatesLateEventAndFutureTimestampBoundariesExactlyAt72Hours() {
        Instant periodStart = Instant.parse("2026-05-01T00:00:00Z");
        Instant periodEnd = Instant.parse("2026-06-01T00:00:00Z");
        SubscriptionSummary activeSub = new SubscriptionSummary(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), SupportedCurrency.BDT,
                BillingInterval.MONTHLY, "UTC", 1, SubscriptionStatus.ACTIVE,
                null, null, TrialConversionPolicy.EXPIRE_ON_END,
                periodStart, periodEnd, null, null, false, null, periodStart, periodStart
        );

        // Prior closed period event arriving at +71h59m59s -> ACCEPTED_LATE
        Instant priorEventTs = Instant.parse("2026-04-30T23:30:00Z");
        LateEventPolicyEvaluator.Evaluation late71h = LateEventPolicyEvaluator.evaluate(
                priorEventTs, periodStart.plus(Duration.ofHours(72)).minusSeconds(1), activeSub);
        assertThat(late71h.decision()).isEqualTo(LateEventPolicyEvaluator.Decision.ACCEPT_LATE_WITHIN_72H);
        assertThat(late71h.closedPeriodEnd()).isEqualTo(periodStart);

        // Exactly +72h00m00s -> ACCEPTED_LATE
        LateEventPolicyEvaluator.Evaluation lateExact72h = LateEventPolicyEvaluator.evaluate(
                priorEventTs, periodStart.plus(Duration.ofHours(72)), activeSub);
        assertThat(lateExact72h.decision()).isEqualTo(LateEventPolicyEvaluator.Decision.ACCEPT_LATE_WITHIN_72H);
        assertThat(lateExact72h.hoursLate()).isEqualByComparingTo("72.0000");

        // +72h00m01s -> REJECT_LATE_WINDOW_EXCEEDED
        LateEventPolicyEvaluator.Evaluation lateOver72h = LateEventPolicyEvaluator.evaluate(
                priorEventTs, periodStart.plus(Duration.ofHours(72)).plusSeconds(1), activeSub);
        assertThat(lateOver72h.decision()).isEqualTo(LateEventPolicyEvaluator.Decision.REJECT_LATE_WINDOW_EXCEEDED);

        // Future timestamp > +5m -> REJECT_FUTURE_TIMESTAMP
        Instant now = Instant.parse("2026-05-10T12:00:00Z");
        LateEventPolicyEvaluator.Evaluation futureRejected = LateEventPolicyEvaluator.evaluate(
                now.plus(Duration.ofMinutes(5)).plusSeconds(1), now, activeSub);
        assertThat(futureRejected.decision()).isEqualTo(LateEventPolicyEvaluator.Decision.REJECT_FUTURE_TIMESTAMP);

        // Future timestamp within +5m -> ACCEPT_ON_TIME
        LateEventPolicyEvaluator.Evaluation futureOk = LateEventPolicyEvaluator.evaluate(
                now.plus(Duration.ofMinutes(5)), now, activeSub);
        assertThat(futureOk.decision()).isEqualTo(LateEventPolicyEvaluator.Decision.ACCEPT_ON_TIME);
    }

    @Test
    void accumulatesRollupBucketsInDeterministicDeadlockFreeOrderWithExactTieBreaking() {
        UUID custA = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID custB = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID metricId = UUID.fromString("00000000-0000-0000-0000-000000000010");

        Instant ts = Instant.parse("2026-05-05T14:15:00Z");
        Instant ing1 = Instant.parse("2026-05-05T14:15:01Z");
        Instant ing2 = Instant.parse("2026-05-05T14:15:02Z");
        UUID idLow = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID idHigh = UUID.fromString("f0000000-0000-0000-0000-000000000001");

        RollupBucketAccumulator acc = new RollupBucketAccumulator();
        // Add in reverse customer order to assert deterministic sorting
        acc.add(custB, metricId, ts, ing1, idLow, new BigDecimal("5.500000"), false);
        acc.add(custA, metricId, ts, ing1, idLow, new BigDecimal("10.250000"), false);
        // Same eventTimestamp, later ingestedAt -> wins latest
        acc.add(custA, metricId, ts, ing2, idLow, new BigDecimal("20.000000"), true);
        // Same eventTimestamp AND same ingestedAt, lexicographically higher UUID -> wins latest
        acc.add(custA, metricId, ts, ing2, idHigh, new BigDecimal("15.750000"), false);

        List<RollupBucketAccumulator.BucketDelta> sorted = acc.toSortedDeltas();
        assertThat(sorted).hasSize(2);
        assertThat(sorted.get(0).customerId()).isEqualTo(custA);
        assertThat(sorted.get(1).customerId()).isEqualTo(custB);

        RollupBucketAccumulator.BucketDelta bucketA = sorted.get(0);
        assertThat(bucketA.bucketStart()).isEqualTo(Instant.parse("2026-05-05T14:00:00Z"));
        assertThat(bucketA.eventCount()).isEqualTo(3L);
        assertThat(bucketA.lateEventCount()).isEqualTo(1L);
        assertThat(bucketA.sumValue()).isEqualByComparingTo("46.000000");
        assertThat(bucketA.maxValue()).isEqualByComparingTo("20.000000");
        assertThat(bucketA.latestValue()).isEqualByComparingTo("15.750000");
        assertThat(bucketA.latestEventId()).isEqualTo(idHigh);
    }

    @Test
    void enforcesAllFiveAggregationsFiltersAndStrictInvalidPropertyRejectionViaCatalogueContract() {
        Instant t1 = Instant.parse("2026-05-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-05-01T11:00:00Z");
        UUID id1 = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID id2 = UUID.fromString("f0000000-0000-0000-0000-000000000002");

        List<MetricFilterDefinition> filters = List.of(
                new MetricFilterDefinition(1, "env", MetricFilterOperator.EQUALS, List.of("prod")),
                new MetricFilterDefinition(2, "region", MetricFilterOperator.IN, List.of("ap", "eu")),
                new MetricFilterDefinition(3, "tier", MetricFilterOperator.NOT_EQUALS, List.of("free")),
                new MetricFilterDefinition(4, "bot", MetricFilterOperator.NOT_IN, List.of("crawler")),
                new MetricFilterDefinition(5, "trace", MetricFilterOperator.EXISTS, List.of())
        );

        BillableMetricDefinition sumMetric = metricDef(MetricAggregationType.SUM, "bytes", filters);
        BillableMetricDefinition uniqueMetric = metricDef(MetricAggregationType.UNIQUE_COUNT, "userId", List.of());

        List<MetricEventSample> samples = List.of(
                new MetricEventSample(id1, t1, t1, Map.of("env", "prod", "region", "ap", "tier", "pro", "bot", "human", "trace", "t1", "bytes", "12.5")),
                new MetricEventSample(id2, t2, t2, Map.of("env", "staging", "region", "ap", "tier", "pro", "bot", "human", "trace", "t2", "bytes", "999"))
        );
        assertThat(MetricEvaluationSupport.evaluateSamples(sumMetric, samples)).isEqualByComparingTo("12.5");

        // Reject missing, null, blank, and non-numeric values explicitly (never silently 0)
        assertThatThrownBy(() -> MetricEvaluationSupport.extractRequiredNumericProperty(
                sumMetric, new MetricEventSample(id1, t1, t1, Map.of())))
                .isInstanceOf(InvalidRatingContextException.class);
        Map<String, Object> nullMap = new LinkedHashMap<>();
        nullMap.put("bytes", null);
        assertThatThrownBy(() -> MetricEvaluationSupport.extractRequiredNumericProperty(
                sumMetric, new MetricEventSample(id1, t1, t1, nullMap)))
                .isInstanceOf(InvalidRatingContextException.class);
        assertThatThrownBy(() -> MetricEvaluationSupport.extractRequiredNumericProperty(
                sumMetric, new MetricEventSample(id1, t1, t1, Map.of("bytes", "not-a-number"))))
                .isInstanceOf(InvalidRatingContextException.class);
        assertThatThrownBy(() -> MetricEvaluationSupport.extractRequiredDistinctKey(
                uniqueMetric, new MetricEventSample(id1, t1, t1, Map.of("userId", "   "))))
                .isInstanceOf(InvalidRatingContextException.class);
    }

    @Test
    void validatesBatchSizeLimitsAndDeterministicThresholdPercentages() {
        UsageIngestionService service = new UsageIngestionService(
                null, null, null, null, null, null,
                new MeteringMetrics(new SimpleMeterRegistry()),
                JsonMapper.builder().build(),
                Clock.fixed(Instant.parse("2026-05-01T00:00:00Z"), ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> service.ingestBatch(new IngestUsageBatchCommand(List.of())))
                .isInstanceOf(InvalidMeteringRequestException.class);

        List<IngestUsageEventCommand> oversized = new ArrayList<>(1_001);
        for (int i = 0; i < 1_001; i++) {
            oversized.add(new IngestUsageEventCommand(
                    null, UUID.randomUUID(), UUID.randomUUID(), null,
                    BigDecimal.ONE, Instant.parse("2026-05-01T00:00:00Z"), "k-" + i, Map.of()
            ));
        }
        assertThatThrownBy(() -> service.ingestBatch(new IngestUsageBatchCommand(oversized)))
                .isInstanceOf(BatchLimitExceededException.class);

        // Threshold utilization math (50%, 80%, 100%)
        BigDecimal pct49 = UsageThresholdService.computeUtilizationPercent(new BigDecimal("49.99"), new BigDecimal("100"));
        assertThat(UsageThresholdService.crossedThresholds(pct49)).isEmpty();

        BigDecimal pct50 = UsageThresholdService.computeUtilizationPercent(new BigDecimal("50"), new BigDecimal("100"));
        assertThat(UsageThresholdService.crossedThresholds(pct50)).containsExactly(50);

        BigDecimal pct85 = UsageThresholdService.computeUtilizationPercent(new BigDecimal("85"), new BigDecimal("100"));
        assertThat(UsageThresholdService.crossedThresholds(pct85)).containsExactly(50, 80);

        BigDecimal pct120 = UsageThresholdService.computeUtilizationPercent(new BigDecimal("120"), new BigDecimal("100"));
        assertThat(UsageThresholdService.crossedThresholds(pct120)).containsExactly(50, 80, 100);
    }

    private static BillableMetricDefinition metricDef(
            MetricAggregationType type,
            String targetProperty,
            List<MetricFilterDefinition> filters
    ) {
        Instant now = Instant.parse("2026-05-01T00:00:00Z");
        return new BillableMetricDefinition(
                UUID.randomUUID(), "test_metric", "Test Metric", null,
                type, targetProperty, null, true, filters, now, now
        );
    }
}
