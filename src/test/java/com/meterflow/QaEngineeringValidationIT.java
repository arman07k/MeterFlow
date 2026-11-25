package com.meterflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterflow.billing.api.BillingRunCommand;
import com.meterflow.billing.api.BillingRunFacade;
import com.meterflow.billing.api.BillingRunSummary;
import com.meterflow.billing.api.FinalizeInvoiceCommand;
import com.meterflow.billing.api.GenerateDraftInvoiceCommand;
import com.meterflow.billing.api.InvoiceDetail;
import com.meterflow.billing.api.InvoiceManagementFacade;
import com.meterflow.billing.api.InvoiceStatus;
import com.meterflow.catalog.api.BillableMetricDefinition;
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
import com.meterflow.metering.api.LiveUsageEstimateRequest;
import com.meterflow.metering.api.LiveUsageEstimateResult;
import com.meterflow.metering.api.RollupReconciliationResult;
import com.meterflow.metering.api.UsageIngestionFacade;
import com.meterflow.metering.api.UsageQueryFacade;
import com.meterflow.metering.api.UsageReconciliationFacade;
import com.meterflow.shared.BillingInterval;
import com.meterflow.shared.SupportedCurrency;
import com.meterflow.subscriptions.api.CreateSubscriptionCommand;
import com.meterflow.subscriptions.api.SubscriptionManagementFacade;
import com.meterflow.subscriptions.api.SubscriptionSummary;
import com.meterflow.subscriptions.api.TrialConversionPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
 * Reproducible QA1, QA2, QA3, and QA5 engineering validation benchmark against PostgreSQL 17 Testcontainers.
 * Records genuine measured throughput, latency, reconciliation precision, batch timing, and estimate accuracy.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(QaEngineeringValidationIT.PostgresContainerConfiguration.class)
class QaEngineeringValidationIT {

    private static final Logger log = LoggerFactory.getLogger(QaEngineeringValidationIT.class);

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
    @Autowired private UsageQueryFacade usageQueryFacade;
    @Autowired private UsageReconciliationFacade usageReconciliationFacade;
    @Autowired private InvoiceManagementFacade invoiceManagementFacade;
    @Autowired private BillingRunFacade billingRunFacade;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void validatesQa1AccuracyQa2IngestionThroughputQa3BatchScalabilityAndQa5Traceability() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        BillableMetricDefinition metric = metricCatalogFacade.createMetric(new CreateMetricCommand(
                "qa_req_" + suffix,
                "QA API Requests",
                "Benchmark API request counter",
                MetricAggregationType.SUM,
                "quantity",
                null,
                List.of()
        ));

        PlanVersionDetail planDraft = planCatalogFacade.createPlanWithDraft(new CreatePlanCommand(
                "QA-PLAN-" + suffix,
                "QA Benchmark Plan",
                "Flat + Per-Unit plan",
                SupportedCurrency.BDT,
                Instant.parse("2026-01-01T00:00:00Z")
        ));
        planCatalogFacade.updateDraftVersion(planDraft.id(), new UpdateDraftPlanVersionCommand(
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of(
                        new ConfigureChargeCommand(1, "FLAT", "Platform Base", "FLAT_FEE", null,
                                new BigDecimal("500.00"), null, null, null, List.of(), Map.of()),
                        new ConfigureChargeCommand(2, "USAGE", "API Request Rate", "PER_UNIT", metric.id(),
                                null, new BigDecimal("0.25"), null, null, List.of(), Map.of())
                ),
                List.of(),
                List.of()
        ));
        PlanVersionDetail plan = planCatalogFacade.publishVersion(planDraft.id());

        // --- QA1 + QA2 + QA5: 5,000 unique events + 150 duplicates (3%) + 100 late events (2%) ---
        CustomerSummary primaryCustomer = identityManagementFacade.createCustomer(
                "qa-primary-" + suffix, "QA Primary Tenant", SupportedCurrency.BDT
        );
        Instant anchorStart = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS).minus(24, java.time.temporal.ChronoUnit.HOURS);
        SubscriptionSummary primarySub = subscriptionManagementFacade.createSubscription(new CreateSubscriptionCommand(
                primaryCustomer.id(), plan.id(), SupportedCurrency.BDT, BillingInterval.MONTHLY, "UTC", 1, anchorStart, null, TrialConversionPolicy.EXPIRE_ON_END, "qa-sub-" + suffix
        ));
        Instant periodStart = primarySub.currentPeriodStart();
        Instant periodEnd = primarySub.currentPeriodEnd();

        int totalUnique = 5_000;
        int batchSize = 1_000;
        List<Long> batchLatenciesMs = new ArrayList<>();
        long ingestStartNanos = System.nanoTime();
        int acceptedTotal = 0;

        for (int b = 0; b < totalUnique / batchSize; b++) {
            List<IngestUsageEventCommand> batch = new ArrayList<>(batchSize);
            for (int i = 0; i < batchSize; i++) {
                int idx = b * batchSize + i;
                batch.add(new IngestUsageEventCommand(
                        UUID.randomUUID(),
                        primaryCustomer.id(),
                        metric.id(),
                        primarySub.id(),
                        BigDecimal.ONE,
                        periodStart.plusSeconds(60L + (idx % 36000)),
                        "qa-ev-" + suffix + "-" + idx,
                        Map.of("quantity", 1)
                ));
            }
            long bStart = System.nanoTime();
            BatchIngestionResult res = usageIngestionFacade.ingestBatch(new IngestUsageBatchCommand(batch));
            long bMs = (System.nanoTime() - bStart) / 1_000_000L;
            batchLatenciesMs.add(bMs);
            acceptedTotal += res.acceptedCount();
        }
        long ingestElapsedMs = Math.max(1L, (System.nanoTime() - ingestStartNanos) / 1_000_000L);
        double eventsPerSecond = (totalUnique * 1000.0) / ingestElapsedMs;

        // 3% duplicate replay batch (150 duplicates)
        List<IngestUsageEventCommand> duplicateBatch = new ArrayList<>(150);
        for (int i = 0; i < 150; i++) {
            duplicateBatch.add(new IngestUsageEventCommand(
                    UUID.randomUUID(),
                    primaryCustomer.id(),
                    metric.id(),
                    primarySub.id(),
                    BigDecimal.ONE,
                    periodStart.plusSeconds(60L + (i % 36000)),
                    "qa-ev-" + suffix + "-" + i,
                    Map.of("quantity", 1)
            ));
        }
        BatchIngestionResult dupRes = usageIngestionFacade.ingestBatch(new IngestUsageBatchCommand(duplicateBatch));
        assertThat(acceptedTotal).isEqualTo(totalUnique);
        assertThat(dupRes.duplicateCount()).isEqualTo(150);
        assertThat(dupRes.acceptedCount()).isZero();

        // 2% late-arriving events (100 events outside open period -> classified as late)
        List<IngestUsageEventCommand> lateBatch = new ArrayList<>(100);
        for (int i = 0; i < 100; i++) {
            lateBatch.add(new IngestUsageEventCommand(
                    UUID.randomUUID(),
                    primaryCustomer.id(),
                    metric.id(),
                    primarySub.id(),
                    BigDecimal.ONE,
                    periodStart.minusSeconds(3600L + i),
                    "qa-late-" + suffix + "-" + i,
                    Map.of("quantity", 1)
            ));
        }
        BatchIngestionResult lateRes = usageIngestionFacade.ingestBatch(new IngestUsageBatchCommand(lateBatch));
        assertThat(lateRes.lateAcceptedCount()).isEqualTo(100);

        // Exact reconciliation check (QA1): raw events == rollups == 5,000
        RollupReconciliationResult recon = usageReconciliationFacade.reconcileRollups(
                primaryCustomer.id(), metric.id(), periodStart, periodEnd
        );
        assertThat(recon.reconciledSum()).isEqualByComparingTo(new BigDecimal("5000"));
        assertThat(recon.reconciledEventCount()).isEqualTo(5_000L);

        // Live estimate vs Finalized invoice accuracy check (QA5: < 1% deviation)
        LiveUsageEstimateResult liveEstimate = usageQueryFacade.estimateLiveUsage(
                new LiveUsageEstimateRequest(primaryCustomer.id(), List.of(metric.id()))
        );
        InvoiceDetail draft = invoiceManagementFacade.generateOrRefreshDraft(new GenerateDraftInvoiceCommand(
                primarySub.id(), periodStart, periodEnd, 0, null, "qa-draft-" + suffix
        ));
        InvoiceDetail finalized = invoiceManagementFacade.finalizeInvoice(
                draft.invoice().id(), new FinalizeInvoiceCommand(false, periodEnd.plusSeconds(86400), "qa-fin-" + suffix)
        );
        // Expected: 500.00 flat + 5000 * 0.25 (1250.00) = 1750.00 BDT exact to 0.00 paisa
        assertThat(finalized.invoice().subtotalAmount().amount()).isEqualByComparingTo(new BigDecimal("1750.00"));
        assertThat(liveEstimate.estimatedTotalAmount().amount()).isEqualByComparingTo(new BigDecimal("1750.00"));
        BigDecimal deviationPct = liveEstimate.estimatedTotalAmount().amount()
                .subtract(finalized.invoice().subtotalAmount().amount())
                .abs()
                .multiply(BigDecimal.valueOf(100))
                .divide(finalized.invoice().subtotalAmount().amount(), 4, RoundingMode.HALF_UP);
        assertThat(deviationPct).isLessThan(BigDecimal.ONE);
        assertThat(finalized.lines()).allSatisfy(line -> assertThat(line.calculationBreakdown()).isNotEmpty());

        // --- QA3: 50-customer scaled Spring Batch draft + finalization run + idempotency ---
        int batchCustomerCount = 50;
        Instant batchStart = Instant.parse("2026-08-01T00:00:00Z");
        Instant batchEnd = Instant.parse("2026-09-01T00:00:00Z");
        for (int i = 0; i < batchCustomerCount; i++) {
            CustomerSummary c = identityManagementFacade.createCustomer(
                    "qa-batch-" + suffix + "-" + i, "Batch Tenant " + i, SupportedCurrency.BDT
            );
            subscriptionManagementFacade.createSubscription(new CreateSubscriptionCommand(
                    c.id(), plan.id(), SupportedCurrency.BDT, BillingInterval.MONTHLY, "UTC", 1, batchStart, null, TrialConversionPolicy.EXPIRE_ON_END, "qa-bsub-" + suffix + "-" + i
            ));
        }

        long draftRunStart = System.nanoTime();
        BillingRunSummary draftRun = billingRunFacade.runDraftGenerationBatch(
                new BillingRunCommand(null, batchEnd.plusSeconds(10), 0, "qa-drun-" + suffix)
        );
        long draftRunMs = (System.nanoTime() - draftRunStart) / 1_000_000L;

        long finRunStart = System.nanoTime();
        BillingRunSummary finRun = billingRunFacade.runFinalizationBatch(
                new BillingRunCommand(null, batchEnd.plusSeconds(10), 0, "qa-frun-" + suffix)
        );
        long finRunMs = (System.nanoTime() - finRunStart) / 1_000_000L;

        // Re-running finalization must be idempotent (0 duplicate invoices)
        Integer finalizedCountBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoices WHERE status = 'FINALIZED' AND period_start = ?",
                Integer.class,
                java.sql.Timestamp.from(batchStart)
        );
        billingRunFacade.runFinalizationBatch(
                new BillingRunCommand(null, batchEnd.plusSeconds(10), 0, "qa-frun2-" + suffix)
        );
        Integer finalizedCountAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoices WHERE status = 'FINALIZED' AND period_start = ?",
                Integer.class,
                java.sql.Timestamp.from(batchStart)
        );
        assertThat(finalizedCountBefore).isGreaterThanOrEqualTo(batchCustomerCount);
        assertThat(finalizedCountAfter).isEqualTo(finalizedCountBefore);
        assertThat(finalized.invoice().status()).isEqualTo(InvoiceStatus.FINALIZED);

        Collections.sort(batchLatenciesMs);
        long p50 = batchLatenciesMs.get(batchLatenciesMs.size() / 2);
        long p95 = batchLatenciesMs.get(Math.min(batchLatenciesMs.size() - 1, (int) Math.ceil(batchLatenciesMs.size() * 0.95) - 1));
        long p99 = batchLatenciesMs.get(batchLatenciesMs.size() - 1);

        log.info("QA_BENCHMARK_METRICS: qa1_reconciled=true qa1_unique=5000 qa1_duplicates=150 qa1_late=100 "
                        + "qa2_elapsed_ms={} qa2_events_per_sec={} qa2_batch1000_p50_ms={} qa2_batch1000_p95_ms={} qa2_batch1000_p99_ms={} "
                        + "qa3_customers={} qa3_draft_run_ms={} qa3_finalize_run_ms={} qa3_draft_status={} qa3_finalize_status={} "
                        + "qa5_estimate_deviation_pct={}",
                ingestElapsedMs,
                String.format("%.1f", eventsPerSecond),
                p50, p95, p99,
                batchCustomerCount,
                draftRunMs,
                finRunMs,
                draftRun.status(),
                finRun.status(),
                deviationPct.toPlainString());
    }
}
