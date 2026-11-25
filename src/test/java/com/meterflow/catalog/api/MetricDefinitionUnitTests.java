package com.meterflow.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MetricDefinitionUnitTests {

    private static final Instant T1 = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-01T11:00:00Z");
    private static final Instant I1 = Instant.parse("2026-11-01T11:00:01Z");
    private static final Instant I2 = Instant.parse("2026-11-01T11:00:05Z");

    @Test
    void evaluatesAllFiveMandatoryAggregationTypesDeterministically() {
        List<MetricEventSample> samples = List.of(
                new MetricEventSample(UUID.fromString("00000000-0000-0000-0000-000000000001"), T1, I1,
                        Map.of("bytes", "10.50", "userId", "u1", "region", "ap-south")),
                new MetricEventSample(UUID.fromString("00000000-0000-0000-0000-000000000002"), T2, I1,
                        Map.of("bytes", new BigDecimal("25.25"), "userId", "u2", "region", "ap-south")),
                new MetricEventSample(UUID.fromString("00000000-0000-0000-0000-000000000003"), T2, I2,
                        Map.of("bytes", 15, "userId", "u1", "region", "ap-south"))
        );

        assertThat(MetricEvaluationSupport.evaluateSamples(metric(MetricAggregationType.SUM, "bytes", List.of()), samples))
                .isEqualByComparingTo("50.75");
        assertThat(MetricEvaluationSupport.evaluateSamples(metric(MetricAggregationType.COUNT, null, List.of()), samples))
                .isEqualByComparingTo("3");
        assertThat(MetricEvaluationSupport.evaluateSamples(metric(MetricAggregationType.MAXIMUM, "bytes", List.of()), samples))
                .isEqualByComparingTo("25.25");
        assertThat(MetricEvaluationSupport.evaluateSamples(metric(MetricAggregationType.UNIQUE_COUNT, "userId", List.of()), samples))
                .isEqualByComparingTo("2");
        assertThat(MetricEvaluationSupport.evaluateSamples(metric(MetricAggregationType.LATEST_VALUE, "bytes", List.of()), samples))
                .isEqualByComparingTo("15");
    }

    @Test
    void breaksLatestValueTiesDeterministicallyByEventTimestampThenIngestedAtThenEventId() {
        UUID lowId = UUID.fromString("00000000-0000-0000-0000-000000000010");
        UUID highId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

        // Tie on eventTimestamp AND ingestedAt -> higher UUID wins deterministically
        List<MetricEventSample> tiedAll = List.of(
                new MetricEventSample(lowId, T2, I2, Map.of("gauge", "100")),
                new MetricEventSample(highId, T2, I2, Map.of("gauge", "200"))
        );
        BillableMetricDefinition latestMetric = metric(MetricAggregationType.LATEST_VALUE, "gauge", List.of());
        assertThat(MetricEvaluationSupport.evaluateSamples(latestMetric, tiedAll))
                .isEqualByComparingTo("200");

        // Tie on eventTimestamp -> later ingestedAt wins even if eventId is smaller
        List<MetricEventSample> tiedTimestamp = List.of(
                new MetricEventSample(highId, T2, I1, Map.of("gauge", "100")),
                new MetricEventSample(lowId, T2, I2, Map.of("gauge", "350"))
        );
        assertThat(MetricEvaluationSupport.evaluateSamples(latestMetric, tiedTimestamp))
                .isEqualByComparingTo("350");
    }

    @Test
    void evaluatesAllFiveMandatoryFilterOperators() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("region", "bd-dhaka");
        props.put("tier", "premium");
        props.put("status", "200");
        props.put("optionalNull", null);

        assertThat(MetricEvaluationSupport.matchesFilter(
                new MetricFilterDefinition(1, "region", MetricFilterOperator.EQUALS, List.of("bd-dhaka")), props)).isTrue();
        assertThat(MetricEvaluationSupport.matchesFilter(
                new MetricFilterDefinition(1, "region", MetricFilterOperator.EQUALS, List.of("us-east")), props)).isFalse();

        assertThat(MetricEvaluationSupport.matchesFilter(
                new MetricFilterDefinition(1, "region", MetricFilterOperator.NOT_EQUALS, List.of("us-east")), props)).isTrue();
        assertThat(MetricEvaluationSupport.matchesFilter(
                new MetricFilterDefinition(1, "region", MetricFilterOperator.NOT_EQUALS, List.of("bd-dhaka")), props)).isFalse();

        assertThat(MetricEvaluationSupport.matchesFilter(
                new MetricFilterDefinition(1, "tier", MetricFilterOperator.IN, List.of("standard", "premium")), props)).isTrue();
        assertThat(MetricEvaluationSupport.matchesFilter(
                new MetricFilterDefinition(1, "tier", MetricFilterOperator.IN, List.of("free")), props)).isFalse();

        assertThat(MetricEvaluationSupport.matchesFilter(
                new MetricFilterDefinition(1, "status", MetricFilterOperator.NOT_IN, List.of("500", "503")), props)).isTrue();
        assertThat(MetricEvaluationSupport.matchesFilter(
                new MetricFilterDefinition(1, "status", MetricFilterOperator.NOT_IN, List.of("200", "201")), props)).isFalse();

        assertThat(MetricEvaluationSupport.matchesFilter(
                new MetricFilterDefinition(1, "region", MetricFilterOperator.EXISTS, List.of()), props)).isTrue();
        assertThat(MetricEvaluationSupport.matchesFilter(
                new MetricFilterDefinition(1, "optionalNull", MetricFilterOperator.EXISTS, List.of()), props)).isFalse();
        assertThat(MetricEvaluationSupport.matchesFilter(
                new MetricFilterDefinition(1, "missingKey", MetricFilterOperator.EXISTS, List.of()), props)).isFalse();
    }

    @Test
    void explicitlyRejectsMissingNullAndInvalidNumericPropertiesWithoutCoercingToZero() {
        BillableMetricDefinition sumMetric = metric(MetricAggregationType.SUM, "tokens", List.of());

        MetricEventSample missing = new MetricEventSample(UUID.randomUUID(), T1, I1, Map.of("other", "5"));
        Map<String, Object> withNull = new LinkedHashMap<>();
        withNull.put("tokens", null);
        MetricEventSample nullProp = new MetricEventSample(UUID.randomUUID(), T1, I1, withNull);
        MetricEventSample nonNumeric = new MetricEventSample(UUID.randomUUID(), T1, I1, Map.of("tokens", "invalid"));
        MetricEventSample blankStr = new MetricEventSample(UUID.randomUUID(), T1, I1, Map.of("tokens", "   "));
        MetricEventSample nanDouble = new MetricEventSample(UUID.randomUUID(), T1, I1, Map.of("tokens", Double.NaN));

        assertThatThrownBy(() -> MetricEvaluationSupport.evaluateSamples(sumMetric, List.of(missing)))
                .isInstanceOf(InvalidRatingContextException.class)
                .hasMessageContaining("Missing");
        assertThatThrownBy(() -> MetricEvaluationSupport.evaluateSamples(sumMetric, List.of(nullProp)))
                .isInstanceOf(InvalidRatingContextException.class)
                .hasMessageContaining("Null");
        assertThatThrownBy(() -> MetricEvaluationSupport.evaluateSamples(sumMetric, List.of(nonNumeric)))
                .isInstanceOf(InvalidRatingContextException.class)
                .hasMessageContaining("non-numeric");
        assertThatThrownBy(() -> MetricEvaluationSupport.evaluateSamples(sumMetric, List.of(blankStr)))
                .isInstanceOf(InvalidRatingContextException.class);
        assertThatThrownBy(() -> MetricEvaluationSupport.evaluateSamples(sumMetric, List.of(nanDouble)))
                .isInstanceOf(InvalidRatingContextException.class);
    }

    private static BillableMetricDefinition metric(
            MetricAggregationType type,
            String targetProp,
            List<MetricFilterDefinition> filters
    ) {
        return new BillableMetricDefinition(
                UUID.randomUUID(),
                "test_metric",
                "Test Metric",
                null,
                type,
                targetProp,
                null,
                true,
                filters,
                T1,
                T1
        );
    }
}
