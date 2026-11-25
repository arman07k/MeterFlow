package com.meterflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meterflow.catalog.api.BillableMetricDefinition;
import com.meterflow.catalog.api.ChargeTypes;
import com.meterflow.catalog.api.ConfigureChargeCommand;
import com.meterflow.catalog.api.CreateMetricCommand;
import com.meterflow.catalog.api.CreatePlanCommand;
import com.meterflow.catalog.api.MetricAggregationType;
import com.meterflow.catalog.api.MetricCatalogFacade;
import com.meterflow.catalog.api.MetricFilterDefinition;
import com.meterflow.catalog.api.MetricFilterOperator;
import com.meterflow.catalog.api.PlanCatalogFacade;
import com.meterflow.catalog.api.PlanVersionDetail;
import com.meterflow.catalog.api.UpdateDraftPlanVersionCommand;
import com.meterflow.catalog.api.UpdateMetricCommand;
import com.meterflow.identity.api.CustomerSummary;
import com.meterflow.identity.api.IdentityManagementFacade;
import com.meterflow.identity.api.MembershipRole;
import com.meterflow.identity.api.PlatformRole;
import com.meterflow.identity.api.UserSummary;
import com.meterflow.metering.api.BatchIngestionResult;
import com.meterflow.metering.api.EventIngestionItemResult;
import com.meterflow.metering.api.EventIngestionOutcome;
import com.meterflow.metering.api.IngestUsageBatchCommand;
import com.meterflow.metering.api.IngestUsageEventCommand;
import com.meterflow.metering.api.LateUsageEventRecord;
import com.meterflow.metering.api.LateUsageQueryFacade;
import com.meterflow.metering.api.LiveUsageEstimateRequest;
import com.meterflow.metering.api.LiveUsageEstimateResult;
import com.meterflow.metering.api.RollupReconciliationResult;
import com.meterflow.metering.api.ThresholdEvaluationCommand;
import com.meterflow.metering.api.ThresholdEvaluationResult;
import com.meterflow.metering.api.UsageAggregationResult;
import com.meterflow.metering.api.UsageDataSource;
import com.meterflow.metering.api.UsageIngestionFacade;
import com.meterflow.metering.api.UsageQueryFacade;
import com.meterflow.metering.api.UsageQueryRequest;
import com.meterflow.metering.api.UsageReconciliationFacade;
import com.meterflow.metering.api.UsageThresholdFacade;
import com.meterflow.shared.BillingInterval;
import com.meterflow.shared.CacheNames;
import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import com.meterflow.subscriptions.api.CreateSubscriptionCommand;
import com.meterflow.subscriptions.api.SubscriptionManagementFacade;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(MeteringModuleIT.PostgresContainerConfiguration.class)
class MeteringModuleIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresContainerConfiguration {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer("postgres:17-alpine");
        }
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private IdentityManagementFacade identityManagement;

    @Autowired
    private MetricCatalogFacade metricCatalog;

    @Autowired
    private PlanCatalogFacade planCatalog;

    @Autowired
    private SubscriptionManagementFacade subscriptionManagement;

    @Autowired
    private UsageIngestionFacade usageIngestion;

    @Autowired
    private UsageQueryFacade usageQuery;

    @Autowired
    private LateUsageQueryFacade lateUsageQuery;

    @Autowired
    private UsageReconciliationFacade usageReconciliation;

    @Autowired
    private UsageThresholdFacade usageThreshold;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @LocalServerPort
    private int serverPort;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private Clock clock;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    usage_threshold_evaluations,
                    usage_rejected_events,
                    usage_late_event_records,
                    usage_rollups,
                    usage_events,
                    event_publication,
                    subscription_state_transitions,
                    subscription_proration_records,
                    subscription_scheduled_changes,
                    subscription_plan_assignments,
                    subscriptions,
                    minimum_commitments,
                    free_allowances,
                    charge_tiers,
                    plan_charges,
                    plan_versions,
                    pricing_plans,
                    metric_filters,
                    billable_metrics,
                    customer_memberships,
                    users,
                    customers
                RESTART IDENTITY CASCADE
                """);
        cacheManager.getCache(CacheNames.PLAN_VERSIONS).clear();
        cacheManager.getCache(CacheNames.METRIC_DEFINITIONS).clear();
        cacheManager.getCache(CacheNames.LIVE_ESTIMATES).clear();
    }

    @Test
    void enforcesPartialBatchAcceptanceDatabaseIdempotencyConflictDetectionAndConcurrentDuplicateSafety() throws Exception {
        CustomerSummary custA = identityManagement.createCustomer("met-a", "Meter A", SupportedCurrency.BDT);
        CustomerSummary custB = identityManagement.createCustomer("met-b", "Meter B", SupportedCurrency.BDT);

        BillableMetricDefinition sumMetric = metricCatalog.createMetric(new CreateMetricCommand(
                "tokens_sum", "Tokens", null, MetricAggregationType.SUM, "tokens", null, List.of()
        ));
        BillableMetricDefinition disabledMetric = metricCatalog.createMetric(new CreateMetricCommand(
                "disabled_m", "Disabled", null, MetricAggregationType.COUNT, null, null, List.of()
        ));
        metricCatalog.updateMetric(disabledMetric.id(), new UpdateMetricCommand(null, null, null, false, null));

        BillableMetricDefinition custBPrivateMetric = metricCatalog.createMetric(new CreateMetricCommand(
                "b_private", "B Private", null, MetricAggregationType.COUNT, null, custB.id(), List.of()
        ));

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant ts = now.minus(Duration.ofMinutes(10));

        // Partial batch: 1 valid, 1 intra-batch duplicate, 1 intra-batch conflict, 1 invalid negative qty,
        // 1 invalid non-numeric property, 1 disabled metric, 1 cross-customer metric ownership violation
        BatchIngestionResult partial = usageIngestion.ingestBatch(new IngestUsageBatchCommand(List.of(
                new IngestUsageEventCommand(null, custA.id(), sumMetric.id(), null, new BigDecimal("50"), ts, "k-1", Map.of("tokens", 50)),
                new IngestUsageEventCommand(null, custA.id(), sumMetric.id(), null, new BigDecimal("50.00"), ts, "k-1", Map.of("tokens", 50)),
                new IngestUsageEventCommand(null, custA.id(), sumMetric.id(), null, new BigDecimal("99"), ts, "k-1", Map.of("tokens", 99)),
                new IngestUsageEventCommand(null, custA.id(), sumMetric.id(), null, new BigDecimal("-5"), ts, "k-neg", Map.of("tokens", 5)),
                new IngestUsageEventCommand(null, custA.id(), sumMetric.id(), null, BigDecimal.ONE, ts, "k-badprop", Map.of("tokens", "NaN")),
                new IngestUsageEventCommand(null, custA.id(), disabledMetric.id(), null, BigDecimal.ONE, ts, "k-dis", Map.of()),
                new IngestUsageEventCommand(null, custA.id(), custBPrivateMetric.id(), null, BigDecimal.ONE, ts, "k-own", Map.of())
        )));

        assertThat(partial.totalReceived()).isEqualTo(7);
        assertThat(partial.acceptedCount()).isEqualTo(1);
        assertThat(partial.duplicateCount()).isEqualTo(1);
        assertThat(partial.conflictCount()).isEqualTo(1);
        assertThat(partial.rejectedCount()).isEqualTo(4);

        // Re-submitting k-1 identically -> DUPLICATE; with different payload -> CONFLICT
        EventIngestionItemResult replayDup = usageIngestion.ingestSingle(new IngestUsageEventCommand(
                null, custA.id(), sumMetric.id(), null, new BigDecimal("50"), ts, "k-1", Map.of("tokens", 50)));
        assertThat(replayDup.outcome()).isEqualTo(EventIngestionOutcome.DUPLICATE);

        EventIngestionItemResult replayConflict = usageIngestion.ingestSingle(new IngestUsageEventCommand(
                null, custA.id(), sumMetric.id(), null, new BigDecimal("77"), ts, "k-1", Map.of("tokens", 77)));
        assertThat(replayConflict.outcome()).isEqualTo(EventIngestionOutcome.CONFLICT);

        // Batch containing TWO identical copies of already-committed key k-1 with a DIFFERENT payload -> BOTH must be CONFLICT
        BatchIngestionResult doubleConflictBatch = usageIngestion.ingestBatch(new IngestUsageBatchCommand(List.of(
                new IngestUsageEventCommand(null, custA.id(), sumMetric.id(), null, new BigDecimal("88"), ts, "k-1", Map.of("tokens", 88)),
                new IngestUsageEventCommand(null, custA.id(), sumMetric.id(), null, new BigDecimal("88"), ts, "k-1", Map.of("tokens", 88))
        )));
        assertThat(doubleConflictBatch.conflictCount()).isEqualTo(2);
        assertThat(doubleConflictBatch.duplicateCount()).isZero();

        // Batch containing TWO identical copies of already-committed key k-1 with MATCHING payload -> BOTH return committed DB eventId
        UUID committedK1Id = partial.results().getFirst().eventId();
        BatchIngestionResult doubleDupBatch = usageIngestion.ingestBatch(new IngestUsageBatchCommand(List.of(
                new IngestUsageEventCommand(null, custA.id(), sumMetric.id(), null, new BigDecimal("50"), ts, "k-1", Map.of("tokens", 50)),
                new IngestUsageEventCommand(null, custA.id(), sumMetric.id(), null, new BigDecimal("50"), ts, "k-1", Map.of("tokens", 50))
        )));
        assertThat(doubleDupBatch.duplicateCount()).isEqualTo(2);
        assertThat(doubleDupBatch.results()).allSatisfy(r -> assertThat(r.eventId()).isEqualTo(committedK1Id));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM usage_events", Integer.class)).isEqualTo(1);

        // Concurrent duplicate storm: 6 threads submitting the exact same 50 unique events simultaneously
        List<IngestUsageEventCommand> sharedBatch = new ArrayList<>(50);
        for (int i = 0; i < 50; i++) {
            sharedBatch.add(new IngestUsageEventCommand(
                    null, custA.id(), sumMetric.id(), null, new BigDecimal("10"), ts.plusSeconds(i),
                    "conc-" + i, Map.of("tokens", 10)
            ));
        }

        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<BatchIngestionResult>> tasks = new ArrayList<>();
            for (int t = 0; t < 6; t++) {
                tasks.add(() -> usageIngestion.ingestBatch(new IngestUsageBatchCommand(sharedBatch)));
            }
            int totalAccepted = 0;
            int totalDuplicates = 0;
            for (Future<BatchIngestionResult> f : pool.invokeAll(tasks)) {
                BatchIngestionResult res = f.get();
                assertThat(res.conflictCount()).isZero();
                assertThat(res.rejectedCount()).isZero();
                totalAccepted += res.acceptedCount();
                totalDuplicates += res.duplicateCount();
            }
            assertThat(totalAccepted).isEqualTo(50);
            assertThat(totalDuplicates).isEqualTo(250);
        } finally {
            pool.shutdownNow();
        }

        // Verify rollup sum == 50 (from k-1) + 50 * 10 = 550.000000 with zero double counting
        UsageAggregationResult agg = usageQuery.queryMetricUsage(new UsageQueryRequest(
                custA.id(), sumMetric.id(), ts.minus(Duration.ofHours(1)), ts.plus(Duration.ofHours(2))
        ));
        assertThat(agg.aggregatedQuantity()).isEqualByComparingTo("550.000000");
        assertThat(agg.matchedEventCount()).isEqualTo(51L);
    }

    @Test
    void computesAllFiveAggregationsFiltersSubHourEdgesTieBreakingAndTraceability() {
        CustomerSummary cust = identityManagement.createCustomer("agg-c", "Agg Customer", SupportedCurrency.USD);
        Instant base = clock.instant().truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(3));

        BillableMetricDefinition mSum = metricCatalog.createMetric(new CreateMetricCommand(
                "m_sum", "Sum", null, MetricAggregationType.SUM, "units", null,
                List.of(new MetricFilterDefinition(1, "env", MetricFilterOperator.EQUALS, List.of("prod")))
        ));
        BillableMetricDefinition mCount = metricCatalog.createMetric(new CreateMetricCommand(
                "m_count", "Count", null, MetricAggregationType.COUNT, null, null, List.of()
        ));
        BillableMetricDefinition mMax = metricCatalog.createMetric(new CreateMetricCommand(
                "m_max", "Max", null, MetricAggregationType.MAXIMUM, "cpu", null, List.of()
        ));
        BillableMetricDefinition mUnique = metricCatalog.createMetric(new CreateMetricCommand(
                "m_unique", "Unique Users", null, MetricAggregationType.UNIQUE_COUNT, "userId", null, List.of()
        ));
        BillableMetricDefinition mLatest = metricCatalog.createMetric(new CreateMetricCommand(
                "m_latest", "Latest Gauge", null, MetricAggregationType.LATEST_VALUE, "seats", null, List.of()
        ));

        UUID lowId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID highId = UUID.fromString("f0000000-0000-0000-0000-000000000001");

        usageIngestion.ingestBatch(new IngestUsageBatchCommand(List.of(
                // SUM with filter: 2 prod events (10.5 + 20.25 = 30.75), 1 staging event ignored by filter
                new IngestUsageEventCommand(null, cust.id(), mSum.id(), null, new BigDecimal("10.5"), base.plus(Duration.ofMinutes(15)), "s1", Map.of("env", "prod", "region", "us", "units", "10.5")),
                new IngestUsageEventCommand(null, cust.id(), mSum.id(), null, new BigDecimal("20.25"), base.plus(Duration.ofMinutes(70)), "s2", Map.of("env", "prod", "region", "eu", "units", "20.25")),
                new IngestUsageEventCommand(null, cust.id(), mSum.id(), null, new BigDecimal("500"), base.plus(Duration.ofMinutes(80)), "s3", Map.of("env", "staging", "region", "us", "units", "500")),
                // COUNT
                new IngestUsageEventCommand(null, cust.id(), mCount.id(), null, BigDecimal.ONE, base.plus(Duration.ofMinutes(5)), "c1", Map.of()),
                new IngestUsageEventCommand(null, cust.id(), mCount.id(), null, BigDecimal.ONE, base.plus(Duration.ofMinutes(65)), "c2", Map.of()),
                // MAXIMUM
                new IngestUsageEventCommand(null, cust.id(), mMax.id(), null, BigDecimal.ONE, base.plus(Duration.ofMinutes(10)), "mx1", Map.of("cpu", 42.5)),
                new IngestUsageEventCommand(null, cust.id(), mMax.id(), null, BigDecimal.ONE, base.plus(Duration.ofMinutes(70)), "mx2", Map.of("cpu", 98.75)),
                // UNIQUE_COUNT: 3 events, 2 distinct users
                new IngestUsageEventCommand(null, cust.id(), mUnique.id(), null, BigDecimal.ONE, base.plus(Duration.ofMinutes(10)), "u1", Map.of("userId", "alice")),
                new IngestUsageEventCommand(null, cust.id(), mUnique.id(), null, BigDecimal.ONE, base.plus(Duration.ofMinutes(20)), "u2", Map.of("userId", "bob")),
                new IngestUsageEventCommand(null, cust.id(), mUnique.id(), null, BigDecimal.ONE, base.plus(Duration.ofMinutes(65)), "u3", Map.of("userId", "alice")),
                // LATEST_VALUE with identical eventTimestamp & ingestedAt -> lexicographically higher UUID wins (99)
                new IngestUsageEventCommand(lowId, cust.id(), mLatest.id(), null, BigDecimal.ONE, base.plus(Duration.ofMinutes(30)), "l1", Map.of("seats", 12)),
                new IngestUsageEventCommand(highId, cust.id(), mLatest.id(), null, BigDecimal.ONE, base.plus(Duration.ofMinutes(30)), "l2", Map.of("seats", 99))
        )));

        Instant fullEnd = base.plus(Duration.ofHours(2));

        // Full hour-aligned window reads directly from ROLLUP
        UsageAggregationResult sumRes = usageQuery.queryMetricUsage(
                new UsageQueryRequest(cust.id(), mSum.id(), base, fullEnd, List.of(), true));
        assertThat(sumRes.aggregatedQuantity()).isEqualByComparingTo("30.750000");
        assertThat(sumRes.matchedEventCount()).isEqualTo(2L);
        assertThat(sumRes.dataSource()).isEqualTo(UsageDataSource.ROLLUP);
        assertThat(sumRes.traceableEventIds()).hasSize(2);

        // Ad-hoc runtime filter (region=eu) narrows result to 20.25 via MetricEvaluationSupport
        UsageAggregationResult filteredRes = usageQuery.queryMetricUsage(new UsageQueryRequest(
                cust.id(), mSum.id(), base, fullEnd,
                List.of(new MetricFilterDefinition(1, "region", MetricFilterOperator.EQUALS, List.of("eu"))),
                true
        ));
        assertThat(filteredRes.aggregatedQuantity()).isEqualByComparingTo("20.25");
        assertThat(filteredRes.dataSource()).isEqualTo(UsageDataSource.RAW_EVENTS);
        assertThat(filteredRes.traceableEventIds()).hasSize(1);

        // Sub-hour boundary slice uses ROLLUP_AND_EDGES accurately
        UsageAggregationResult edgeRes = usageQuery.queryMetricUsage(new UsageQueryRequest(
                cust.id(), mSum.id(), base.plus(Duration.ofMinutes(10)), fullEnd
        ));
        assertThat(edgeRes.aggregatedQuantity()).isEqualByComparingTo("30.750000");
        assertThat(edgeRes.dataSource()).isEqualTo(UsageDataSource.ROLLUP_AND_EDGES);

        assertThat(usageQuery.queryMetricUsage(new UsageQueryRequest(cust.id(), mCount.id(), base, fullEnd))
                .aggregatedQuantity()).isEqualByComparingTo("2");
        assertThat(usageQuery.queryMetricUsage(new UsageQueryRequest(cust.id(), mMax.id(), base, fullEnd))
                .aggregatedQuantity()).isEqualByComparingTo("98.750000");
        assertThat(usageQuery.queryMetricUsage(new UsageQueryRequest(cust.id(), mUnique.id(), base, fullEnd))
                .aggregatedQuantity()).isEqualByComparingTo("2");
        assertThat(usageQuery.queryMetricUsage(new UsageQueryRequest(cust.id(), mLatest.id(), base, fullEnd))
                .aggregatedQuantity()).isEqualByComparingTo("99.000000");
    }

    @Test
    void handles72HourLateEventsDownstreamConsumptionReconciliationAndPendingRollupRecovery() {
        CustomerSummary cust = identityManagement.createCustomer("late-c", "Late Customer", SupportedCurrency.USD);
        BillableMetricDefinition apiMetric = metricCatalog.createMetric(new CreateMetricCommand(
                "late_api", "Late API", null, MetricAggregationType.SUM, "calls", null, List.of()
        ));
        PlanVersionDetail plan = createPublishedPlan("late-plan", SupportedCurrency.USD, apiMetric.id(), "0.50");

        // Subscription current period started 24 hours ago => prior period closed 24h ago (within 72h window!)
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant currentPeriodStart = now.minus(Duration.ofHours(24));
        subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                cust.id(), plan.id(), SupportedCurrency.USD, BillingInterval.MONTHLY,
                "UTC", 1, currentPeriodStart, null, null, "sub-late-1"
        ));

        // Event timestamp before currentPeriodStart while currentPeriodStart was 24h ago -> ACCEPTED_LATE
        Instant priorPeriodTs = currentPeriodStart.minus(Duration.ofHours(2));
        EventIngestionItemResult lateResult = usageIngestion.ingestSingle(new IngestUsageEventCommand(
                null, cust.id(), apiMetric.id(), null, new BigDecimal("40"), priorPeriodTs, "late-k1", Map.of("calls", 40)
        ));
        assertThat(lateResult.outcome()).isEqualTo(EventIngestionOutcome.ACCEPTED_LATE);

        // Downstream Billing late-event contract sees pending record & can mark consumed
        List<LateUsageEventRecord> pending = lateUsageQuery
                .listPendingLateEvents(cust.id(), PageRequest.of(0, 10)).getContent();
        assertThat(pending).hasSize(1);
        assertThat(pending.getFirst().usageEventId()).isEqualTo(lateResult.eventId());
        assertThat(lateUsageQuery.markLateEventsConsumed(List.of(pending.getFirst().id()))).isEqualTo(1);
        assertThat(lateUsageQuery.listPendingLateEvents(cust.id(), PageRequest.of(0, 10)).getContent()).isEmpty();

        // Simulate a subscription whose period closed 80 hours ago (> 72h) -> REJECTED (LATE_WINDOW_EXCEEDED)
        CustomerSummary expiredCust = identityManagement.createCustomer("exp-c", "Expired Late", SupportedCurrency.USD);
        Instant oldPeriodStart = now.minus(Duration.ofHours(80));
        subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                expiredCust.id(), plan.id(), SupportedCurrency.USD, BillingInterval.MONTHLY,
                "UTC", 1, oldPeriodStart, null, null, "sub-exp-1"
        ));
        EventIngestionItemResult tooLate = usageIngestion.ingestSingle(new IngestUsageEventCommand(
                null, expiredCust.id(), apiMetric.id(), null, new BigDecimal("10"),
                oldPeriodStart.minus(Duration.ofHours(1)), "too-late-1", Map.of("calls", 10)
        ));
        assertThat(tooLate.outcome()).isEqualTo(EventIngestionOutcome.REJECTED);
        assertThat(tooLate.errorCode()).isEqualTo("LATE_WINDOW_EXCEEDED");

        // Historical >72h duplicate replay (FR1: idempotency check executes before 72h expiry):
        // Backdate cust's current_period_start to 80h ago so priorPeriodTs is now >72h past period close,
        // then replay already-committed "late-k1" -> must return DUPLICATE (same payload) and CONFLICT (different payload).
        jdbcTemplate.update(
                "UPDATE subscriptions SET current_period_start = ? WHERE customer_id = ?",
                Timestamp.from(now.minus(Duration.ofHours(80))),
                cust.id()
        );
        EventIngestionItemResult historicalDup = usageIngestion.ingestSingle(new IngestUsageEventCommand(
                null, cust.id(), apiMetric.id(), null, new BigDecimal("40"), priorPeriodTs, "late-k1", Map.of("calls", 40)
        ));
        assertThat(historicalDup.outcome()).isEqualTo(EventIngestionOutcome.DUPLICATE);
        assertThat(historicalDup.eventId()).isEqualTo(lateResult.eventId());

        EventIngestionItemResult historicalConflict = usageIngestion.ingestSingle(new IngestUsageEventCommand(
                null, cust.id(), apiMetric.id(), null, new BigDecimal("999"), priorPeriodTs, "late-k1", Map.of("calls", 999)
        ));
        assertThat(historicalConflict.outcome()).isEqualTo(EventIngestionOutcome.CONFLICT);

        // QA3 Crash Recovery & Rollup Reconciliation:
        // 1. Insert a simulated unrolled PENDING_ROLLUP row directly (as if interrupted before rollup)
        UUID pendingEventId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO usage_events (
                    id, customer_id, metric_id, idempotency_key, payload_hash,
                    quantity, measured_value, event_timestamp, ingested_at,
                    properties, filter_matched, late_event, late_classification, processing_status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '{}'::jsonb, TRUE, FALSE, 'ON_TIME', 'PENDING_ROLLUP')
                """,
                pendingEventId, cust.id(), apiMetric.id(), "recovered-k1",
                "a".repeat(64), new BigDecimal("25.000000"), new BigDecimal("25.000000"),
                Timestamp.from(priorPeriodTs.plus(Duration.ofMinutes(5))), Timestamp.from(now)
        );
        assertThat(usageReconciliation.recoverPendingRollups(100)).isEqualTo(1);

        // 2. Intentionally corrupt usage_rollups and verify reconcileRollups restores exact authoritative sum (40 + 25 = 65)
        jdbcTemplate.update("UPDATE usage_rollups SET sum_value = 999999 WHERE customer_id = ?", cust.id());
        RollupReconciliationResult reconciled = usageReconciliation.reconcileRollups(
                cust.id(), apiMetric.id(), priorPeriodTs.minus(Duration.ofHours(1)), priorPeriodTs.plus(Duration.ofHours(2))
        );
        assertThat(reconciled.reconciledSum()).isEqualByComparingTo("65.000000");
        assertThat(reconciled.reconciledEventCount()).isEqualTo(2L);
    }

    @Test
    void computesLiveEstimatesWithAfterCommitCacheEvictionAndDeduplicates50_80_100Thresholds() {
        CustomerSummary cust = identityManagement.createCustomer("live-c", "Live Cust", SupportedCurrency.BDT);
        BillableMetricDefinition reqMetric = metricCatalog.createMetric(new CreateMetricCommand(
                "live_req", "Live Requests", null, MetricAggregationType.SUM, "count", null, List.of()
        ));
        PlanVersionDetail plan = createPublishedPlan("live-plan", SupportedCurrency.BDT, reqMetric.id(), "2.00");

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant periodStart = now.minus(Duration.ofHours(2));
        subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                cust.id(), plan.id(), SupportedCurrency.BDT, BillingInterval.MONTHLY,
                "UTC", 1, periodStart, null, null, "sub-live-1"
        ));

        usageIngestion.ingestSingle(new IngestUsageEventCommand(
                null, cust.id(), reqMetric.id(), null, new BigDecimal("100"),
                now.minus(Duration.ofMinutes(10)), "live-1", Map.of("count", 100)
        ));

        // First live estimate: cache miss (cached=false), 100 * 2.00 + 500.00 flat = 700.00 BDT
        LiveUsageEstimateResult est1 = usageQuery.estimateLiveUsage(new LiveUsageEstimateRequest(cust.id(), List.of()));
        assertThat(est1.cached()).isFalse();
        assertThat(est1.maxFreshnessSeconds()).isEqualTo(15L);
        assertThat(est1.estimatedTotalAmount()).isEqualTo(Money.bdt("700.00"));

        // Second live estimate immediately: served from Caffeine (cached=true)
        LiveUsageEstimateResult est2 = usageQuery.estimateLiveUsage(new LiveUsageEstimateRequest(cust.id(), List.of()));
        assertThat(est2.cached()).isTrue();
        assertThat(est2.estimatedTotalAmount()).isEqualTo(Money.bdt("700.00"));

        // New event ingestion evicts Caffeine liveEstimates after commit -> fresh total 900.00 BDT!
        usageIngestion.ingestSingle(new IngestUsageEventCommand(
                null, cust.id(), reqMetric.id(), null, new BigDecimal("100"),
                now.minus(Duration.ofMinutes(5)), "live-2", Map.of("count", 100)
        ));
        LiveUsageEstimateResult est3 = usageQuery.estimateLiveUsage(new LiveUsageEstimateRequest(cust.id(), List.of()));
        assertThat(est3.cached()).isFalse();
        assertThat(est3.estimatedTotalAmount()).isEqualTo(Money.bdt("900.00"));

        // FR7 Threshold evaluation (50%, 80%, 100%) with strict deduplication
        Instant wStart = periodStart;
        Instant wEnd = periodStart.plus(Duration.ofDays(30));
        ThresholdEvaluationResult eval55 = usageThreshold.evaluateThresholds(new ThresholdEvaluationCommand(
                cust.id(), est3.subscriptionId(), reqMetric.id(), "budget-main",
                wStart, wEnd, new BigDecimal("1000"), new BigDecimal("550")
        ));
        assertThat(eval55.newlyTriggered()).extracting(t -> t.thresholdPercent()).containsExactly(50);

        // Retry at 55% -> zero new triggers, 50% listed as already triggered
        ThresholdEvaluationResult retry55 = usageThreshold.evaluateThresholds(new ThresholdEvaluationCommand(
                cust.id(), est3.subscriptionId(), reqMetric.id(), "budget-main",
                wStart, wEnd, new BigDecimal("1000"), new BigDecimal("550")
        ));
        assertThat(retry55.newlyTriggered()).isEmpty();
        assertThat(retry55.alreadyTriggeredPercents()).containsExactly(50);

        // Jump to 105% -> triggers ONLY 80% and 100% (never re-triggers 50%)
        ThresholdEvaluationResult eval105 = usageThreshold.evaluateThresholds(new ThresholdEvaluationCommand(
                cust.id(), est3.subscriptionId(), reqMetric.id(), "budget-main",
                wStart, wEnd, new BigDecimal("1000"), new BigDecimal("1050")
        ));
        assertThat(eval105.newlyTriggered()).extracting(t -> t.thresholdPercent()).containsExactly(80, 100);
        assertThat(eval105.alreadyTriggeredPercents()).containsExactly(50);
        assertThat(usageThreshold.listTriggeredThresholds(cust.id(), wStart, wEnd)).hasSize(3);
    }

    @Test
    void enforcesRestAuthorizationCustomerIsolationAndRfc9457ErrorResponses() throws Exception {
        CustomerSummary tenantA = identityManagement.createCustomer("mrest-a", "Tenant A", SupportedCurrency.BDT);
        CustomerSummary tenantB = identityManagement.createCustomer("mrest-b", "Tenant B", SupportedCurrency.BDT);
        UserSummary operator = identityManagement.createUser(
                "met-ops@meterflow.example", "Meter Ops", "MeterOpsPass#2026", PlatformRole.OPERATOR);
        UserSummary custUserA = identityManagement.createUser(
                "met-a@meterflow.example", "Meter Cust A", "MeterCustAPass#2026", PlatformRole.CUSTOMER);
        identityManagement.assignMembership(custUserA.id(), tenantA.id(), MembershipRole.OWNER);

        BillableMetricDefinition metric = metricCatalog.createMetric(new CreateMetricCommand(
                "rest_calls", "REST Calls", null, MetricAggregationType.COUNT, null, null, List.of()
        ));
        Instant ts = clock.instant().minus(Duration.ofMinutes(5)).truncatedTo(ChronoUnit.MICROS);

        // Customer A cannot call operator batch ingestion -> 403
        String opBatchBody = """
                {
                  "events": [
                    {
                      "customerId": "%s",
                      "metricId": "%s",
                      "quantity": 1,
                      "eventTimestamp": "%s",
                      "idempotencyKey": "rest-1",
                      "properties": {}
                    }
                  ]
                }
                """.formatted(tenantA.id(), metric.id(), ts);

        mockMvc.perform(post("/api/v1/events/batch")
                        .with(httpBasic(custUserA.email(), "MeterCustAPass#2026"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(opBatchBody))
                .andExpect(status().isForbidden());

        // Customer A CAN ingest own event via customer-scoped endpoint -> 202
        mockMvc.perform(post("/api/v1/customer/" + tenantA.id() + "/metering/events/batch")
                        .with(httpBasic(custUserA.email(), "MeterCustAPass#2026"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(opBatchBody))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.acceptedCount").value(1));

        // Customer A CANNOT spoof Tenant B's customerId inside customer-scoped body -> 403
        String spoofedBody = opBatchBody.replace(tenantA.id().toString(), tenantB.id().toString());
        mockMvc.perform(post("/api/v1/customer/" + tenantA.id() + "/metering/events/batch")
                        .with(httpBasic(custUserA.email(), "MeterCustAPass#2026"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(spoofedBody))
                .andExpect(status().isForbidden());

        // Customer A CANNOT read Tenant B's live estimate -> 403
        mockMvc.perform(get("/api/v1/customer/" + tenantB.id() + "/metering/usage/live-estimate")
                        .with(httpBasic(custUserA.email(), "MeterCustAPass#2026")))
                .andExpect(status().isForbidden());

        // Operator single-event duplicate -> 200 OK; conflicting payload -> 409 RFC 9457 ProblemDetail
        String singleDup = """
                {
                  "customerId": "%s",
                  "metricId": "%s",
                  "quantity": 1,
                  "eventTimestamp": "%s",
                  "idempotencyKey": "rest-1",
                  "properties": {}
                }
                """.formatted(tenantA.id(), metric.id(), ts);
        mockMvc.perform(post("/api/v1/events")
                        .with(httpBasic(operator.email(), "MeterOpsPass#2026"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleDup))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DUPLICATE"));

        String singleConflict = singleDup.replace("\"quantity\": 1", "\"quantity\": 9");
        mockMvc.perform(post("/api/v1/events")
                        .with(httpBasic(operator.email(), "MeterOpsPass#2026"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleConflict))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:meterflow:error:idempotency-key-conflict"));
    }

    @Test
    void measuresRealHttpBatchThroughputLatencyAndIndependentReconciliationOnPostgres17() throws Exception {
        CustomerSummary cust = identityManagement.createCustomer("bench-c", "Benchmark Cust", SupportedCurrency.USD);
        UserSummary operator = identityManagement.createUser(
                "bench-ops@meterflow.example", "Bench Ops", "BenchOpsPass#2026", PlatformRole.OPERATOR);
        BillableMetricDefinition metric = metricCatalog.createMetric(new CreateMetricCommand(
                "bench_units", "Benchmark Units", null, MetricAggregationType.SUM, "units", null, List.of()
        ));
        PlanVersionDetail plan = createPublishedPlan("bench-plan", SupportedCurrency.USD, metric.id(), "0.01");

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant periodStart = now.minus(Duration.ofHours(12));
        subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                cust.id(), plan.id(), SupportedCurrency.USD, BillingInterval.MONTHLY,
                "UTC", 1, periodStart, null, null, "bench-sub-1"
        ));

        int totalEvents = Integer.getInteger("meterflow.qa.events", 20_000);
        int batchSize = Integer.getInteger("meterflow.qa.batchSize", 200);
        int totalBatches = totalEvents / batchSize;
        int concurrency = Integer.getInteger("meterflow.qa.threads", 4);

        // Warm up JVM JIT and Hikari pool before measuring
        for (int w = 0; w < 2; w++) {
            usageIngestion.ingestSingle(new IngestUsageEventCommand(
                    null, cust.id(), metric.id(), null, BigDecimal.ONE,
                    periodStart.plus(Duration.ofSeconds(10 + w)), "warmup-" + w, Map.of("units", 1)
            ));
        }
        jdbcTemplate.execute("TRUNCATE TABLE usage_late_event_records, usage_rollups, usage_events, event_publication CASCADE");

        // 1. Direct Service + PostgreSQL 17 ingestion benchmark (isolates DB + Service latency without HTTP Basic BCrypt)
        List<IngestUsageBatchCommand> serviceBatches = new ArrayList<>(totalBatches);
        long expectedUniqueCount = 0L;
        long expectedLateCount = 0L;
        long expectedSum = 0L;
        for (int b = 0; b < totalBatches; b++) {
            List<IngestUsageEventCommand> cmds = new ArrayList<>(batchSize);
            for (int i = 0; i < batchSize; i++) {
                int g = b * batchSize + i;
                int blockBase = (g / 100) * 100;
                int offset = g % 100;
                int canonical = offset >= 97 ? blockBase + (offset - 97) : g;
                boolean late = offset >= 95 && offset < 97;
                if (offset < 97) {
                    expectedUniqueCount++;
                    expectedSum += (canonical % 10) + 1;
                    if (late) {
                        expectedLateCount++;
                    }
                }
                int qty = (canonical % 10) + 1;
                Instant ts = late
                        ? periodStart.minus(Duration.ofSeconds(60 + (canonical % 3600)))
                        : periodStart.plus(Duration.ofSeconds(60 + (canonical % 3600)));
                cmds.add(new IngestUsageEventCommand(
                        null, cust.id(), metric.id(), null, BigDecimal.valueOf(qty),
                        ts, "qa-svc-" + canonical, Map.of("units", qty)
                ));
            }
            serviceBatches.add(new IngestUsageBatchCommand(cmds));
        }

        double[] svcLatenciesMs = new double[totalBatches];
        ExecutorService svcPool = Executors.newFixedThreadPool(concurrency);
        long svcWallStart = System.nanoTime();
        try {
            List<Callable<Void>> tasks = new ArrayList<>(totalBatches);
            for (int b = 0; b < totalBatches; b++) {
                final int idx = b;
                tasks.add(() -> {
                    long t0 = System.nanoTime();
                    BatchIngestionResult res = usageIngestion.ingestBatch(serviceBatches.get(idx));
                    svcLatenciesMs[idx] = (System.nanoTime() - t0) / 1_000_000.0;
                    assertThat(res.conflictCount()).isZero();
                    assertThat(res.rejectedCount()).isZero();
                    return null;
                });
            }
            for (Future<Void> f : svcPool.invokeAll(tasks)) {
                f.get();
            }
        } finally {
            svcPool.shutdownNow();
        }
        double svcWallSec = (System.nanoTime() - svcWallStart) / 1_000_000_000.0;
        double svcThroughput = totalEvents / svcWallSec;
        Arrays.sort(svcLatenciesMs);
        double svcP50Ms = svcLatenciesMs[(int) (totalBatches * 0.50)];
        double svcP95Ms = svcLatenciesMs[Math.min(totalBatches - 1, (int) Math.ceil(totalBatches * 0.95) - 1)];

        Instant windowStart = periodStart.minus(Duration.ofHours(2));
        Instant windowEnd = periodStart.plus(Duration.ofHours(2));
        UsageAggregationResult svcQueried = usageQuery.queryMetricUsage(
                new UsageQueryRequest(cust.id(), metric.id(), windowStart, windowEnd));
        RollupReconciliationResult svcReconciled = usageReconciliation.reconcileRollups(
                cust.id(), metric.id(), windowStart, windowEnd);
        assertThat(svcQueried.matchedEventCount()).isEqualTo(expectedUniqueCount);
        assertThat(svcQueried.lateEventCount()).isEqualTo(expectedLateCount);
        assertThat(svcQueried.aggregatedQuantity()).isEqualByComparingTo(BigDecimal.valueOf(expectedSum));
        assertThat(svcReconciled.reconciledSum()).isEqualByComparingTo(BigDecimal.valueOf(expectedSum));

        // 2. Full HTTP end-to-end benchmark (includes Spring Security HTTP Basic BCrypt + JSON + Service + Postgres)
        jdbcTemplate.execute("TRUNCATE TABLE usage_late_event_records, usage_rollups, usage_events, event_publication CASCADE");

        String authHeader = "Basic " + Base64.getEncoder().encodeToString(
                (operator.email() + ":BenchOpsPass#2026").getBytes(StandardCharsets.UTF_8));
        URI endpoint = URI.create("http://127.0.0.1:" + serverPort + "/api/v1/events/batch");

        List<String> payloads = new ArrayList<>(totalBatches);
        for (int b = 0; b < totalBatches; b++) {
            StringBuilder sb = new StringBuilder(batchSize * 180);
            sb.append("{\"events\":[");
            for (int i = 0; i < batchSize; i++) {
                int g = b * batchSize + i;
                int blockBase = (g / 100) * 100;
                int offset = g % 100;
                int canonical = offset >= 97 ? blockBase + (offset - 97) : g;
                boolean late = offset >= 95 && offset < 97;
                int qty = (canonical % 10) + 1;
                Instant ts = late
                        ? periodStart.minus(Duration.ofSeconds(60 + (canonical % 3600)))
                        : periodStart.plus(Duration.ofSeconds(60 + (canonical % 3600)));
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"customerId\":\"").append(cust.id())
                        .append("\",\"metricId\":\"").append(metric.id())
                        .append("\",\"quantity\":").append(qty)
                        .append(",\"eventTimestamp\":\"").append(ts)
                        .append("\",\"idempotencyKey\":\"qa-http-").append(canonical)
                        .append("\",\"properties\":{\"units\":").append(qty).append("}}");
            }
            sb.append("]}");
            payloads.add(sb.toString());
        }

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        double[] latenciesMs = new double[totalBatches];
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);

        long wallStart = System.nanoTime();
        try {
            List<Callable<Void>> tasks = new ArrayList<>(totalBatches);
            for (int b = 0; b < totalBatches; b++) {
                final int idx = b;
                tasks.add(() -> {
                    long reqStart = System.nanoTime();
                    HttpRequest req = HttpRequest.newBuilder(endpoint)
                            .header("Authorization", authHeader)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payloads.get(idx)))
                            .build();
                    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                    latenciesMs[idx] = (System.nanoTime() - reqStart) / 1_000_000.0;
                    assertThat(resp.statusCode()).isIn(200, 202);
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(tasks)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        double wallSeconds = (System.nanoTime() - wallStart) / 1_000_000_000.0;
        double eventsPerSec = totalEvents / wallSeconds;

        Arrays.sort(latenciesMs);
        double p50Ms = latenciesMs[(int) (totalBatches * 0.50)];
        double p95Ms = latenciesMs[Math.min(totalBatches - 1, (int) Math.ceil(totalBatches * 0.95) - 1)];

        Timer ingestTimer = meterRegistry.find("meterflow.metering.ingestion.duration").timer();
        Timer rollupTimer = meterRegistry.find("meterflow.metering.rollup.duration").timer();
        double avgIngestMs = ingestTimer != null ? ingestTimer.mean(TimeUnit.MILLISECONDS) : -1.0;
        double avgRollupMs = rollupTimer != null ? rollupTimer.mean(TimeUnit.MILLISECONDS) : -1.0;

        UsageAggregationResult queried = usageQuery.queryMetricUsage(
                new UsageQueryRequest(cust.id(), metric.id(), windowStart, windowEnd));
        RollupReconciliationResult reconciled = usageReconciliation.reconcileRollups(
                cust.id(), metric.id(), windowStart, windowEnd);

        Long rawDbCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM usage_events WHERE customer_id = ? AND metric_id = ?",
                Long.class, cust.id(), metric.id());
        BigDecimal rawDbSum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(measured_value), 0) FROM usage_events WHERE customer_id = ? AND metric_id = ?",
                BigDecimal.class, cust.id(), metric.id());

        assertThat(rawDbCount).isEqualTo(expectedUniqueCount);
        assertThat(rawDbSum).isEqualByComparingTo(BigDecimal.valueOf(expectedSum));
        assertThat(queried.matchedEventCount()).isEqualTo(expectedUniqueCount);
        assertThat(queried.lateEventCount()).isEqualTo(expectedLateCount);
        assertThat(queried.aggregatedQuantity()).isEqualByComparingTo(BigDecimal.valueOf(expectedSum));
        assertThat(reconciled.reconciledEventCount()).isEqualTo(expectedUniqueCount);
        assertThat(reconciled.reconciledSum()).isEqualByComparingTo(BigDecimal.valueOf(expectedSum));

        System.out.printf(
                "METERFLOW_QA_BENCHMARK: submitted=%d unique=%d late=%d batchSize=%d threads=%d svcEvSec=%.1f svcP50Ms=%.2f svcP95Ms=%.2f httpEvSec=%.1f httpP50Ms=%.2f httpP95Ms=%.2f timerIngestMeanMs=%.2f timerRollupMeanMs=%.2f parity=EXACT%n",
                totalEvents, expectedUniqueCount, expectedLateCount, batchSize, concurrency,
                svcThroughput, svcP50Ms, svcP95Ms,
                eventsPerSec, p50Ms, p95Ms, avgIngestMs, avgRollupMs
        );
    }

    private PlanVersionDetail createPublishedPlan(
            String planCode,
            SupportedCurrency currency,
            UUID usageMetricId,
            String unitPrice
    ) {
        PlanVersionDetail draft = planCatalog.createPlanWithDraft(new CreatePlanCommand(
                planCode, planCode.toUpperCase(), "Metering test plan", currency, Instant.parse("2026-01-01T00:00:00Z")
        ));
        planCatalog.updateDraftVersion(draft.id(), new UpdateDraftPlanVersionCommand(
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of(
                        new ConfigureChargeCommand(1, "base", "Base Fee", ChargeTypes.FLAT_FEE, null,
                                new BigDecimal("500.00"), null, null, null, List.of(), Map.of()),
                        new ConfigureChargeCommand(2, "usage", "Usage Charge", ChargeTypes.PER_UNIT, usageMetricId,
                                null, new BigDecimal(unitPrice), null, null, List.of(), Map.of())
                ),
                List.of(),
                List.of()
        ));
        return planCatalog.publishVersion(draft.id());
    }
}
