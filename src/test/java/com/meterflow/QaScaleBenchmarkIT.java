package com.meterflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterflow.billing.api.BillingRunCommand;
import com.meterflow.billing.api.BillingRunFacade;
import com.meterflow.billing.api.BillingRunStatus;
import com.meterflow.billing.api.BillingRunSummary;
import com.meterflow.billing.api.FinalizeInvoiceCommand;
import com.meterflow.billing.api.GenerateDraftInvoiceCommand;
import com.meterflow.billing.api.InvoiceDetail;
import com.meterflow.billing.api.InvoiceManagementFacade;
import com.meterflow.catalog.api.BillableMetricDefinition;
import com.meterflow.catalog.api.ChargeTypes;
import com.meterflow.catalog.api.ConfigureChargeCommand;
import com.meterflow.catalog.api.CreateMetricCommand;
import com.meterflow.catalog.api.CreatePlanCommand;
import com.meterflow.catalog.api.MetricAggregationType;
import com.meterflow.catalog.api.MetricCatalogFacade;
import com.meterflow.catalog.api.PlanCatalogFacade;
import com.meterflow.catalog.api.PlanVersionDetail;
import com.meterflow.catalog.api.UpdateDraftPlanVersionCommand;
import com.meterflow.identity.api.CustomerSummary;
import com.meterflow.identity.api.IdentityManagementFacade;
import com.meterflow.metering.api.BatchIngestionResult;
import com.meterflow.metering.api.IngestUsageBatchCommand;
import com.meterflow.metering.api.IngestUsageEventCommand;
import com.meterflow.metering.api.RollupReconciliationResult;
import com.meterflow.metering.api.UsageIngestionFacade;
import com.meterflow.metering.api.UsageReconciliationFacade;
import com.meterflow.shared.BillingInterval;
import com.meterflow.shared.SupportedCurrency;
import com.meterflow.subscriptions.api.CreateSubscriptionCommand;
import com.meterflow.subscriptions.api.SubscriptionManagementFacade;
import com.meterflow.subscriptions.api.SubscriptionSummary;
import com.meterflow.subscriptions.api.TrialConversionPolicy;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Opt-in full-scale reproducible benchmark suite for QA1 (1,000,000 events + 3% duplicates + 2% late),
 * QA2 (multi-threaded ingestion throughput & p50/p95/p99 latency), and QA3 (10,000-customer Spring Batch
 * draft & finalization billing run).
 *
 * <p>Execute explicitly via:
 * {@code .\mvnw.cmd -B failsafe:integration-test -Dit.test=QaScaleBenchmarkIT -Dmeterflow.scale.benchmark=true}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "meterflow.scale.benchmark", matches = "true")
@Import(QaScaleBenchmarkIT.PostgresContainerConfiguration.class)
class QaScaleBenchmarkIT {

    private static final Logger log = LoggerFactory.getLogger(QaScaleBenchmarkIT.class);

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresContainerConfiguration {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer("postgres:17-alpine");
        }
    }

    @Autowired private IdentityManagementFacade identityManagementFacade;
    @Autowired private MetricCatalogFacade metricCatalogFacade;
    @Autowired private PlanCatalogFacade planCatalogFacade;
    @Autowired private SubscriptionManagementFacade subscriptionManagementFacade;
    @Autowired private UsageIngestionFacade usageIngestionFacade;
    @Autowired private UsageReconciliationFacade usageReconciliationFacade;
    @Autowired private InvoiceManagementFacade invoiceManagementFacade;
    @Autowired private BillingRunFacade billingRunFacade;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void qa1AndQa2FullScaleOneMillionEventIngestionAndReconciliationBenchmark() throws Exception {
        int totalUniqueEvents = Integer.getInteger("meterflow.qa1.events", 1_000_000);
        int batchSize = Integer.getInteger("meterflow.qa1.batchSize", 1_000);
        int threads = Integer.getInteger("meterflow.qa1.threads", 4);
        int customerCount = 10;
        int duplicateTarget = (int) Math.round(totalUniqueEvents * 0.03);
        int lateTarget = (int) Math.round(totalUniqueEvents * 0.02);

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        BillableMetricDefinition metric = metricCatalogFacade.createMetric(new CreateMetricCommand(
                "scale_api_" + suffix, "Scale API Calls", "High-volume API calls",
                MetricAggregationType.SUM, "quantity", null, List.of()
        ));
        PlanVersionDetail draftPlan = planCatalogFacade.createPlanWithDraft(new CreatePlanCommand(
                "SCALE-PLAN-" + suffix, "Scale Plan", "Flat + Per Unit",
                SupportedCurrency.BDT, Instant.parse("2026-01-01T00:00:00Z")
        ));
        planCatalogFacade.updateDraftVersion(draftPlan.id(), new UpdateDraftPlanVersionCommand(
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of(
                        new ConfigureChargeCommand(1, "FLAT", "Base Fee", ChargeTypes.FLAT_FEE, null,
                                new BigDecimal("1000.00"), null, null, null, List.of(), Map.of()),
                        new ConfigureChargeCommand(2, "USAGE", "API Call", ChargeTypes.PER_UNIT, metric.id(),
                                null, new BigDecimal("0.05"), null, null, List.of(), Map.of())
                ),
                List.of(),
                List.of()
        ));
        PlanVersionDetail plan = planCatalogFacade.publishVersion(draftPlan.id());

        Instant anchorStart = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(24, ChronoUnit.HOURS);
        List<CustomerSummary> customers = new ArrayList<>(customerCount);
        List<SubscriptionSummary> subs = new ArrayList<>(customerCount);
        for (int c = 0; c < customerCount; c++) {
            CustomerSummary cust = identityManagementFacade.createCustomer(
                    "scale-c-" + suffix + "-" + c, "Scale Tenant " + c, SupportedCurrency.BDT
            );
            SubscriptionSummary sub = subscriptionManagementFacade.createSubscription(new CreateSubscriptionCommand(
                    cust.id(), plan.id(), SupportedCurrency.BDT, BillingInterval.MONTHLY,
                    "UTC", 1, anchorStart, null, TrialConversionPolicy.EXPIRE_ON_END, "scale-sub-" + suffix + "-" + c
            ));
            customers.add(cust);
            subs.add(sub);
        }

        Instant periodStart = subs.getFirst().currentPeriodStart();
        Instant periodEnd = subs.getFirst().currentPeriodEnd();

        // Warmup 2 batches (not counted in QA2 latency percentiles)
        int totalBatches = totalUniqueEvents / batchSize;
        int batchesPerCustomer = totalBatches / customerCount;
        List<Long> batchLatenciesMs = new CopyOnWriteArrayList<>();
        AtomicLong acceptedCounter = new AtomicLong();
        AtomicLong errorCounter = new AtomicLong();

        long ingestStart = System.nanoTime();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int c = 0; c < customerCount; c++) {
                final int custIdx = c;
                final CustomerSummary cust = customers.get(c);
                final SubscriptionSummary sub = subs.get(c);
                futures.add(pool.submit(() -> {
                    Map<String, Object> fixedProps = Map.of("quantity", 1);
                    for (int b = 0; b < batchesPerCustomer; b++) {
                        List<IngestUsageEventCommand> batch = new ArrayList<>(batchSize);
                        int baseIdx = b * batchSize;
                        for (int i = 0; i < batchSize; i++) {
                            int idx = baseIdx + i;
                            batch.add(new IngestUsageEventCommand(
                                    UUID.randomUUID(),
                                    cust.id(),
                                    metric.id(),
                                    sub.id(),
                                    BigDecimal.ONE,
                                    periodStart.plusSeconds(60L + (idx % 36000)),
                                    "s-" + suffix + "-" + custIdx + "-" + idx,
                                    fixedProps
                            ));
                        }
                        long bStart = System.nanoTime();
                        try {
                            BatchIngestionResult res = usageIngestionFacade.ingestBatch(new IngestUsageBatchCommand(batch));
                            long bMs = (System.nanoTime() - bStart) / 1_000_000L;
                            batchLatenciesMs.add(bMs);
                            acceptedCounter.addAndGet(res.acceptedCount());
                        } catch (Exception ex) {
                            errorCounter.incrementAndGet();
                            throw ex;
                        }
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }
        long ingestElapsedMs = Math.max(1L, (System.nanoTime() - ingestStart) / 1_000_000L);
        double eventsPerSec = (acceptedCounter.get() * 1000.0) / ingestElapsedMs;

        // 3% Duplicate replay batches
        long dupCount = 0;
        int dupPerCustomer = duplicateTarget / customerCount;
        Map<String, Object> fixedProps = Map.of("quantity", 1);
        for (int c = 0; c < customerCount; c++) {
            CustomerSummary cust = customers.get(c);
            SubscriptionSummary sub = subs.get(c);
            for (int offset = 0; offset < dupPerCustomer; offset += batchSize) {
                int chunk = Math.min(batchSize, dupPerCustomer - offset);
                List<IngestUsageEventCommand> dupBatch = new ArrayList<>(chunk);
                for (int i = 0; i < chunk; i++) {
                    int idx = offset + i;
                    dupBatch.add(new IngestUsageEventCommand(
                            UUID.randomUUID(), cust.id(), metric.id(), sub.id(),
                            BigDecimal.ONE, periodStart.plusSeconds(60L + (idx % 36000)),
                            "s-" + suffix + "-" + c + "-" + idx, fixedProps
                    ));
                }
                BatchIngestionResult r = usageIngestionFacade.ingestBatch(new IngestUsageBatchCommand(dupBatch));
                dupCount += r.duplicateCount();
                assertThat(r.acceptedCount()).isZero();
            }
        }

        // 2% Late events within 72h grace window (prior closed period)
        long lateCount = 0;
        int latePerCustomer = lateTarget / customerCount;
        for (int c = 0; c < customerCount; c++) {
            CustomerSummary cust = customers.get(c);
            SubscriptionSummary sub = subs.get(c);
            for (int offset = 0; offset < latePerCustomer; offset += batchSize) {
                int chunk = Math.min(batchSize, latePerCustomer - offset);
                List<IngestUsageEventCommand> lateBatch = new ArrayList<>(chunk);
                for (int i = 0; i < chunk; i++) {
                    int idx = offset + i;
                    lateBatch.add(new IngestUsageEventCommand(
                            UUID.randomUUID(), cust.id(), metric.id(), sub.id(),
                            BigDecimal.ONE, periodStart.minusSeconds(3600L + (idx % 7200)),
                            "sl-" + suffix + "-" + c + "-" + idx, fixedProps
                    ));
                }
                BatchIngestionResult r = usageIngestionFacade.ingestBatch(new IngestUsageBatchCommand(lateBatch));
                lateCount += r.lateAcceptedCount();
            }
        }

        // Also benchmark realistic 250-event batch latency (typical API batch size)
        List<Long> batch250LatenciesMs = new ArrayList<>(40);
        for (int r = 0; r < 40; r++) {
            List<IngestUsageEventCommand> b250 = new ArrayList<>(250);
            CustomerSummary cust = customers.get(r % customerCount);
            SubscriptionSummary sub = subs.get(r % customerCount);
            for (int i = 0; i < 250; i++) {
                b250.add(new IngestUsageEventCommand(
                        UUID.randomUUID(), cust.id(), metric.id(), sub.id(),
                        BigDecimal.ONE, periodStart.plusSeconds(120L + i),
                        "s250-" + suffix + "-" + r + "-" + i, fixedProps
                ));
            }
            long t0 = System.nanoTime();
            usageIngestionFacade.ingestBatch(new IngestUsageBatchCommand(b250));
            batch250LatenciesMs.add((System.nanoTime() - t0) / 1_000_000L);
        }

        // Exact financial reconciliation across all 10 customers down to 0.00 paisa
        long expectedEventsPerCust = (long) batchesPerCustomer * batchSize + (40L / customerCount) * 250L;
        BigDecimal expectedSubtotalPerCust = new BigDecimal("1000.00")
                .add(BigDecimal.valueOf(expectedEventsPerCust).multiply(new BigDecimal("0.05")));

        for (int c = 0; c < customerCount; c++) {
            CustomerSummary cust = customers.get(c);
            SubscriptionSummary sub = subs.get(c);
            RollupReconciliationResult recon = usageReconciliationFacade.reconcileRollups(
                    cust.id(), metric.id(), periodStart, periodEnd
            );
            assertThat(recon.reconciledEventCount()).isEqualTo(expectedEventsPerCust);
            assertThat(recon.reconciledSum()).isEqualByComparingTo(BigDecimal.valueOf(expectedEventsPerCust));

            InvoiceDetail draft = invoiceManagementFacade.generateOrRefreshDraft(
                    new GenerateDraftInvoiceCommand(sub.id(), periodStart, periodEnd, 0, null, "sc-draft-" + suffix + "-" + c)
            );
            InvoiceDetail fin = invoiceManagementFacade.finalizeInvoice(
                    draft.invoice().id(), new FinalizeInvoiceCommand(false, periodEnd.plusSeconds(86400), "sc-fin-" + suffix + "-" + c)
            );
            assertThat(fin.invoice().subtotalAmount().amount()).isEqualByComparingTo(expectedSubtotalPerCust);
        }

        List<Long> sorted1000 = new ArrayList<>(batchLatenciesMs);
        Collections.sort(sorted1000);
        Collections.sort(batch250LatenciesMs);

        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);

        assertThat(acceptedCounter.get()).isEqualTo(totalUniqueEvents);
        assertThat(dupCount).isEqualTo(duplicateTarget);
        assertThat(lateCount).isEqualTo(lateTarget);
        assertThat(errorCounter.get()).isZero();

        log.info("QA1_QA2_SCALE_RESULT: unique={} duplicates={} late={} errors={} elapsed_ms={} throughput_eps={} "
                        + "batch1000_p50_ms={} batch1000_p95_ms={} batch1000_p99_ms={} "
                        + "batch250_p50_ms={} batch250_p95_ms={} batch250_p99_ms={} "
                        + "expected_subtotal_per_cust={} heap_used_mb={} heap_max_mb={}",
                acceptedCounter.get(), dupCount, lateCount, errorCounter.get(),
                ingestElapsedMs, String.format("%.1f", eventsPerSec),
                percentile(sorted1000, 50), percentile(sorted1000, 95), percentile(sorted1000, 99),
                percentile(batch250LatenciesMs, 50), percentile(batch250LatenciesMs, 95), percentile(batch250LatenciesMs, 99),
                expectedSubtotalPerCust.toPlainString(), usedMb, maxMb);
    }

    @Test
    void qa3FullScaleTenThousandCustomerSpringBatchBillingBenchmark() {
        int targetCustomers = Integer.getInteger("meterflow.qa3.customers", 10_000);
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        PlanVersionDetail draftPlan = planCatalogFacade.createPlanWithDraft(new CreatePlanCommand(
                "QA3-PLAN-" + suffix, "QA3 Plan", "Flat recurring monthly",
                SupportedCurrency.BDT, Instant.parse("2026-01-01T00:00:00Z")
        ));
        planCatalogFacade.updateDraftVersion(draftPlan.id(), new UpdateDraftPlanVersionCommand(
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of(new ConfigureChargeCommand(1, "FLAT", "Monthly Subscription", ChargeTypes.FLAT_FEE, null,
                        new BigDecimal("1500.00"), null, null, null, List.of(), Map.of())),
                List.of(),
                List.of()
        ));
        PlanVersionDetail plan = planCatalogFacade.publishVersion(draftPlan.id());

        Instant periodStart = Instant.parse("2026-07-01T00:00:00Z");
        Instant periodEnd = Instant.parse("2026-08-01T00:00:00Z");
        Timestamp nowTs = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
        Timestamp pStartTs = Timestamp.from(periodStart);
        Timestamp pEndTs = Timestamp.from(periodEnd);

        // Fast bulk JDBC seed for 10,000 customers + active subscriptions (setup only; billing runs via real Spring Batch 6)
        long seedStart = System.nanoTime();
        List<Object[]> custRows = new ArrayList<>(targetCustomers);
        List<Object[]> subRows = new ArrayList<>(targetCustomers);
        for (int i = 0; i < targetCustomers; i++) {
            UUID custId = UUID.randomUUID();
            UUID subId = UUID.randomUUID();
            custRows.add(new Object[]{custId, "qa3-" + suffix + "-" + i, "QA3 Customer " + i, "BDT", "ACTIVE", 0L, nowTs, nowTs});
            subRows.add(new Object[]{
                    subId, custId, plan.id(), "BDT", "MONTHLY", "UTC", 1, "ACTIVE",
                    pStartTs, pEndTs, false, "EXPIRE_ON_END", "qa3-sub-" + suffix + "-" + i, 0L, nowTs, nowTs
            });
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO customers (id, external_key, name, currency_code, status, version, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
                custRows
        );
        jdbcTemplate.batchUpdate(
                "INSERT INTO subscriptions (id, customer_id, current_plan_version_id, currency_code, billing_interval, billing_timezone, "
                        + "seat_quantity, status, current_period_start, current_period_end, cancel_at_period_end, "
                        + "trial_conversion_policy, creation_idempotency_key, version, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                subRows
        );
        long seedMs = (System.nanoTime() - seedStart) / 1_000_000L;

        long draftStart = System.nanoTime();
        BillingRunSummary draftRun = billingRunFacade.runDraftGenerationBatch(
                new BillingRunCommand(null, periodEnd.plusSeconds(60), 0, "qa3-draft-" + suffix)
        );
        long draftMs = (System.nanoTime() - draftStart) / 1_000_000L;

        long finStart = System.nanoTime();
        BillingRunSummary finRun = billingRunFacade.runFinalizationBatch(
                new BillingRunCommand(null, periodEnd.plusSeconds(60), 0, "qa3-fin-" + suffix)
        );
        long finMs = (System.nanoTime() - finStart) / 1_000_000L;

        // Idempotent re-run must produce ZERO duplicate invoices
        long rerunStart = System.nanoTime();
        BillingRunSummary rerunFin = billingRunFacade.runFinalizationBatch(
                new BillingRunCommand(null, periodEnd.plusSeconds(60), 0, "qa3-fin-rerun-" + suffix)
        );
        long rerunMs = (System.nanoTime() - rerunStart) / 1_000_000L;

        Integer finalizedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoices WHERE status = 'FINALIZED' AND period_start = ?",
                Integer.class, pStartTs
        );

        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        long totalBillingMs = draftMs + finMs;

        assertThat(draftRun.status()).isEqualTo(BillingRunStatus.COMPLETED);
        assertThat(finRun.status()).isEqualTo(BillingRunStatus.COMPLETED);
        assertThat(rerunFin.status()).isEqualTo(BillingRunStatus.COMPLETED);
        assertThat(finalizedCount).isEqualTo(targetCustomers);

        log.info("QA3_SCALE_RESULT: customers={} seed_ms={} draft_ms={} finalize_ms={} total_billing_ms={} "
                        + "rerun_idempotent_ms={} finalized_invoices={} heap_used_mb={} heap_max_mb={}",
                targetCustomers, seedMs, draftMs, finMs, totalBillingMs, rerunMs, finalizedCount, usedMb, maxMb);
    }

    private static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0L;
        }
        int idx = Math.min(sorted.size() - 1, Math.max(0, (int) Math.ceil(sorted.size() * (p / 100.0)) - 1));
        return sorted.get(idx);
    }
}
