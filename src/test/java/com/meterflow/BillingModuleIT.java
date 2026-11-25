package com.meterflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meterflow.billing.api.BillingAdjustmentFacade;
import com.meterflow.billing.api.BillingAdjustmentRecord;
import com.meterflow.billing.api.BillingAdjustmentStatus;
import com.meterflow.billing.api.BillingAdjustmentType;
import com.meterflow.billing.api.BillingRunCommand;
import com.meterflow.billing.api.BillingRunFacade;
import com.meterflow.billing.api.BillingRunStatus;
import com.meterflow.billing.api.BillingRunSummary;
import com.meterflow.billing.api.ConfigureTaxPolicyCommand;
import com.meterflow.billing.api.CreateManualAdjustmentCommand;
import com.meterflow.billing.api.CreditGrantSummary;
import com.meterflow.billing.api.CreditGrantType;
import com.meterflow.billing.api.CreditNoteDetail;
import com.meterflow.billing.api.CreditNoteFacade;
import com.meterflow.billing.api.CreditNoteLimitExceededException;
import com.meterflow.billing.api.CreditNoteLineInput;
import com.meterflow.billing.api.CreditNoteType;
import com.meterflow.billing.api.CustomerCreditBalance;
import com.meterflow.billing.api.FinalizeInvoiceCommand;
import com.meterflow.billing.api.GenerateDraftInvoiceCommand;
import com.meterflow.billing.api.InvalidInvoiceTransitionException;
import com.meterflow.billing.api.InvoiceDetail;
import com.meterflow.billing.api.InvoiceLineType;
import com.meterflow.billing.api.InvoiceManagementFacade;
import com.meterflow.billing.api.InvoiceStatus;
import com.meterflow.billing.api.IssueCreditGrantCommand;
import com.meterflow.billing.api.IssueCreditNoteCommand;
import com.meterflow.billing.api.LateUsageProcessingSummary;
import com.meterflow.billing.api.PrepaidCreditFacade;
import com.meterflow.billing.api.TaxPolicyFacade;
import com.meterflow.billing.api.VoidInvoiceCommand;
import com.meterflow.catalog.api.AllowanceResetPeriod;
import com.meterflow.catalog.api.BillableMetricDefinition;
import com.meterflow.catalog.api.ChargeTierDefinition;
import com.meterflow.catalog.api.ChargeTypes;
import com.meterflow.catalog.api.ConfigureAllowanceCommand;
import com.meterflow.catalog.api.ConfigureChargeCommand;
import com.meterflow.catalog.api.ConfigureCommitmentCommand;
import com.meterflow.catalog.api.CreateMetricCommand;
import com.meterflow.catalog.api.CreatePlanCommand;
import com.meterflow.catalog.api.MetricAggregationType;
import com.meterflow.catalog.api.MetricCatalogFacade;
import com.meterflow.catalog.api.PlanCatalogFacade;
import com.meterflow.catalog.api.PlanVersionDetail;
import com.meterflow.catalog.api.UpdateDraftPlanVersionCommand;
import com.meterflow.identity.api.CustomerSummary;
import com.meterflow.identity.api.IdentityManagementFacade;
import com.meterflow.identity.api.MembershipRole;
import com.meterflow.identity.api.PlatformRole;
import com.meterflow.identity.api.UserSummary;
import com.meterflow.metering.api.EventIngestionItemResult;
import com.meterflow.metering.api.EventIngestionOutcome;
import com.meterflow.metering.api.IngestUsageEventCommand;
import com.meterflow.metering.api.UsageIngestionFacade;
import com.meterflow.shared.BillingInterval;
import com.meterflow.shared.CurrencyMismatchException;
import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import com.meterflow.subscriptions.api.CreateSubscriptionCommand;
import com.meterflow.subscriptions.api.ImmediatePlanChangeCommand;
import com.meterflow.subscriptions.api.SubscriptionManagementFacade;
import com.meterflow.subscriptions.api.SubscriptionSummary;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
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
@Import(BillingModuleIT.PostgresContainerConfiguration.class)
class BillingModuleIT {

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
    private InvoiceManagementFacade invoiceManagement;

    @Autowired
    private BillingRunFacade billingRunFacade;

    @Autowired
    private TaxPolicyFacade taxPolicyFacade;

    @Autowired
    private PrepaidCreditFacade prepaidCreditFacade;

    @Autowired
    private CreditNoteFacade creditNoteFacade;

    @Autowired
    private BillingAdjustmentFacade billingAdjustmentFacade;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    billing_adjustments,
                    credit_note_lines,
                    credit_notes,
                    credit_transactions,
                    prepaid_credit_grants,
                    invoice_tax_lines,
                    invoice_lines,
                    invoices,
                    tax_policies,
                    billing_runs,
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
                CASCADE
                """);
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        });
    }

    @Test
    void endToEndDraftRefreshFinalizationImmutabilityTriggersAndZeroDoubleBilling() {
        CustomerSummary customer = identityManagement.createCustomer("cust-bdt-01", "Dhaka SaaS Ltd", SupportedCurrency.BDT);
        BillableMetricDefinition apiMetric = metricCatalog.createMetric(new CreateMetricCommand(
                "api_calls_bdt", "API Calls", "Metered API requests",
                MetricAggregationType.SUM, "units", customer.id(), List.of()
        ));

        PlanVersionDetail draftPlan = planCatalog.createPlanWithDraft(
                new CreatePlanCommand("pro-bdt", "Pro BDT", "Hybrid recurring + tiered", SupportedCurrency.BDT,
                        Instant.parse("2026-01-01T00:00:00Z")));
        planCatalog.updateDraftVersion(draftPlan.id(), new UpdateDraftPlanVersionCommand(
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of(
                        new ConfigureChargeCommand(1, "BASE_FEE", "Monthly Platform Fee", ChargeTypes.FLAT_FEE,
                                null, new BigDecimal("500.0000"), null, null, null, List.of(), Map.of()),
                        new ConfigureChargeCommand(2, "SEATS", "Seat License", ChargeTypes.PER_SEAT,
                                null, null, new BigDecimal("100.0000"), null, null, List.of(), Map.of()),
                        new ConfigureChargeCommand(3, "API_CALLS", "Graduated API Calls", ChargeTypes.GRADUATED_TIERS,
                                apiMetric.id(), null, null, null, null,
                                List.of(
                                        new ChargeTierDefinition(1, BigDecimal.ZERO, new BigDecimal("500"),
                                                new BigDecimal("0.5000"), BigDecimal.ZERO, "First 500"),
                                        new ChargeTierDefinition(2, new BigDecimal("500"), null,
                                                new BigDecimal("0.2500"), BigDecimal.ZERO, "Over 500")
                                ), Map.of())
                ),
                List.of(new ConfigureAllowanceCommand(apiMetric.id(), "API_CALLS",
                        new BigDecimal("100"), AllowanceResetPeriod.BILLING_PERIOD)),
                List.of()
        ));
        PlanVersionDetail publishedPlan = planCatalog.publishVersion(draftPlan.id());

        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant periodStart = now.minus(Duration.ofDays(10));
        SubscriptionSummary sub = subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                customer.id(), publishedPlan.id(), SupportedCurrency.BDT, BillingInterval.MONTHLY,
                "UTC", 3, periodStart, null, null, "sub-bdt-01"
        ));

        taxPolicyFacade.configureTaxPolicy(new ConfigureTaxPolicyCommand(
                "BD_VAT_15", "Bangladesh VAT 15%", customer.id(), "BD", "VAT",
                new BigDecimal("15.0000"), false
        ));

        // Ingest 800 API calls inside [periodStart, periodEnd) -> free 100 -> 700 billable (500*0.50 + 200*0.25 = 300)
        EventIngestionItemResult ev1 = usageIngestion.ingestSingle(new IngestUsageEventCommand(
                null, customer.id(), apiMetric.id(), sub.id(),
                new BigDecimal("800"), now.minus(Duration.ofHours(2)), "evt-1", Map.of("units", 800)
        ));
        assertThat(ev1.outcome()).isEqualTo(EventIngestionOutcome.ACCEPTED);

        InvoiceDetail draft1 = invoiceManagement.generateOrRefreshDraft(
                new GenerateDraftInvoiceCommand(sub.id(), sub.currentPeriodStart(), sub.currentPeriodEnd(), 72, null, null));

        // Expected: 500 (flat) + 300 (3 seats) + 300 (usage) = 1100 subtotal, 165 VAT (15%), 1265 total
        assertThat(draft1.invoice().status()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(draft1.invoice().invoiceNumber()).isNull();
        assertThat(draft1.invoice().subtotalAmount()).isEqualTo(Money.bdt("1100.0000"));
        assertThat(draft1.invoice().taxAmount()).isEqualTo(Money.bdt("165.0000"));
        assertThat(draft1.invoice().totalAmount()).isEqualTo(Money.bdt("1265.0000"));
        assertThat(draft1.invoice().payableAmountDue()).isEqualTo(Money.bdt("1265.00"));
        assertThat(draft1.lines()).hasSize(3);
        assertThat(draft1.taxLines()).hasSize(1);
        assertThat(draft1.lines().get(2).calculationBreakdown().get("dataSource"))
                .isIn("ROLLUP_AND_EDGES", "RAW_EVENTS")
                .isNotEqualTo("LIVE_ESTIMATE_CACHE");

        // Additional 200 calls arrive while still DRAFT -> total 1000 -> 900 billable (250 + 100 = 350)
        EventIngestionItemResult ev2 = usageIngestion.ingestSingle(new IngestUsageEventCommand(
                null, customer.id(), apiMetric.id(), sub.id(),
                new BigDecimal("200"), now.minus(Duration.ofHours(1)), "evt-2", Map.of("units", 200)
        ));
        assertThat(ev2.outcome()).isEqualTo(EventIngestionOutcome.ACCEPTED);

        InvoiceDetail draft2 = invoiceManagement.generateOrRefreshDraft(
                new GenerateDraftInvoiceCommand(sub.id(), sub.currentPeriodStart(), sub.currentPeriodEnd(), 72, null, null));

        // Expected: 500 + 300 + 350 = 1150 subtotal, 172.50 VAT, 1322.50 total (same draft ID, refreshed cleanly)
        assertThat(draft2.invoice().id()).isEqualTo(draft1.invoice().id());
        assertThat(draft2.invoice().subtotalAmount()).isEqualTo(Money.bdt("1150.0000"));
        assertThat(draft2.invoice().taxAmount()).isEqualTo(Money.bdt("172.5000"));
        assertThat(draft2.invoice().totalAmount()).isEqualTo(Money.bdt("1322.5000"));
        assertThat(draft2.invoice().payableAmountDue()).isEqualTo(Money.bdt("1322.50"));

        // Finalize invoice
        InvoiceDetail finalized = invoiceManagement.finalizeInvoice(
                draft2.invoice().id(), new FinalizeInvoiceCommand(true, null, "fin-1"));

        assertThat(finalized.invoice().status()).isEqualTo(InvoiceStatus.FINALIZED);
        assertThat(finalized.invoice().invoiceNumber()).startsWith("INV-");
        assertThat(finalized.invoice().finalizedAt()).isNotNull();
        assertThat(finalized.invoice().dueAt()).isNotNull();

        // Outbox event persisted transactionally (distinct event count across all Modulith listeners)
        Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT serialized_event) FROM event_publication WHERE event_type LIKE '%InvoiceFinalizedEvent'",
                Integer.class
        );
        assertThat(outboxCount).isEqualTo(1);

        // Repeatable execution must not create duplicate invoice or mutate finalized amounts
        InvoiceDetail repeated = invoiceManagement.generateOrRefreshDraft(
                new GenerateDraftInvoiceCommand(sub.id(), sub.currentPeriodStart(), sub.currentPeriodEnd(), 72, null, null));
        assertThat(repeated.invoice().id()).isEqualTo(finalized.invoice().id());
        assertThat(repeated.invoice().totalAmount()).isEqualTo(Money.bdt("1322.5000"));
        Integer totalInvoices = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoices", Integer.class);
        assertThat(totalInvoices).isEqualTo(1);

        // Database-level immutability triggers strictly reject tampering on finalized invoice & lines
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE invoices SET total_amount = 9999.0000 WHERE id = ?", finalized.invoice().id()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM invoice_lines WHERE invoice_id = ?", finalized.invoice().id()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void prepaidCreditsEnforceFifoExpiryDrawdownAndPreventConcurrentOverdraft() throws Exception {
        CustomerSummary customer = identityManagement.createCustomer("cust-usd-credit", "Credit Corp", SupportedCurrency.USD);
        PlanVersionDetail draftPlan = planCatalog.createPlanWithDraft(
                new CreatePlanCommand("flat-40-usd", "Flat 40 USD", "Flat recurring", SupportedCurrency.USD,
                        Instant.parse("2026-01-01T00:00:00Z")));
        planCatalog.updateDraftVersion(draftPlan.id(), new UpdateDraftPlanVersionCommand(
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of(new ConfigureChargeCommand(1, "FLAT", "Monthly Flat", ChargeTypes.FLAT_FEE,
                        null, new BigDecimal("40.0000"), null, null, null, List.of(), Map.of())),
                List.of(),
                List.of()
        ));
        PlanVersionDetail published = planCatalog.publishVersion(draftPlan.id());

        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        SubscriptionSummary sub = subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                customer.id(), published.id(), SupportedCurrency.USD, BillingInterval.MONTHLY,
                "UTC", 1, base, null, null, "sub-credit-1"
        ));

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        CreditGrantSummary grantExpiringSoon = prepaidCreditFacade.issueCreditGrant(new IssueCreditGrantCommand(
                customer.id(), SupportedCurrency.USD, CreditGrantType.PROMOTIONAL, "Promo expiring soon",
                new BigDecimal("40.0000"), 100, now.plus(Duration.ofDays(5)), "grant-soon"
        ));
        CreditGrantSummary grantNoExpiry = prepaidCreditFacade.issueCreditGrant(new IssueCreditGrantCommand(
                customer.id(), SupportedCurrency.USD, CreditGrantType.PREPAID, "Prepaid balance",
                new BigDecimal("60.0000"), 100, null, "grant-permanent"
        ));
        // Expired grant must never be consumed
        CreditGrantSummary expiredGrant = prepaidCreditFacade.issueCreditGrant(new IssueCreditGrantCommand(
                customer.id(), SupportedCurrency.USD, CreditGrantType.PROMOTIONAL, "Already expired",
                new BigDecimal("500.0000"), 1, now.minus(Duration.ofDays(1)), "grant-expired"
        ));
        // Idempotent replay of grant issuance must not double credit
        CreditGrantSummary replayGrant = prepaidCreditFacade.issueCreditGrant(new IssueCreditGrantCommand(
                customer.id(), SupportedCurrency.USD, CreditGrantType.PREPAID, "Prepaid balance",
                new BigDecimal("60.0000"), 100, null, "grant-permanent"
        ));
        assertThat(replayGrant.id()).isEqualTo(grantNoExpiry.id());

        CustomerCreditBalance initialBalance = prepaidCreditFacade.getCustomerCreditBalance(customer.id(), SupportedCurrency.USD);
        assertThat(initialBalance.availableBalance()).isEqualTo(Money.usd("100.0000"));
        assertThat(initialBalance.activeGrantCount()).isEqualTo(2);

        // Generate 4 distinct 40 USD draft invoices (160 USD total vs 100 USD available credits)
        List<UUID> draftIds = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Instant pStart = base.plus(Duration.ofDays(i * 30L));
            Instant pEnd = pStart.plus(Duration.ofDays(30));
            InvoiceDetail draft = invoiceManagement.generateOrRefreshDraft(
                    new GenerateDraftInvoiceCommand(sub.id(), pStart, pEnd, 0, null, "draft-conc-" + i));
            draftIds.add(draft.invoice().id());
        }

        // Concurrently finalize all 4 invoices across 4 threads to verify SELECT ... FOR UPDATE prevents overdraft
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        List<Callable<InvoiceDetail>> tasks = draftIds.stream()
                .map(id -> (Callable<InvoiceDetail>) () -> {
                    startGate.await(5, TimeUnit.SECONDS);
                    return invoiceManagement.finalizeInvoice(id, new FinalizeInvoiceCommand(true, null, null));
                })
                .toList();
        List<Future<InvoiceDetail>> futures = tasks.stream().map(executor::submit).toList();
        startGate.countDown();

        Money totalCreditsApplied = Money.usd("0.0000");
        Money totalAmountDue = Money.usd("0.0000");
        for (Future<InvoiceDetail> future : futures) {
            InvoiceDetail finalized = future.get(30, TimeUnit.SECONDS);
            totalCreditsApplied = totalCreditsApplied.add(finalized.invoice().creditsAppliedAmount());
            totalAmountDue = totalAmountDue.add(finalized.invoice().amountDue());
        }
        executor.shutdown();

        assertThat(totalCreditsApplied).isEqualTo(Money.usd("100.0000"));
        assertThat(totalAmountDue).isEqualTo(Money.usd("60.0000"));

        CustomerCreditBalance afterBalance = prepaidCreditFacade.getCustomerCreditBalance(customer.id(), SupportedCurrency.USD);
        assertThat(afterBalance.availableBalance()).isEqualTo(Money.usd("0.0000"));

        // Verify expired grant remained untouched while active grants reached exact 0.0000
        BigDecimal soonRemaining = jdbcTemplate.queryForObject(
                "SELECT remaining_amount FROM prepaid_credit_grants WHERE id = ?",
                BigDecimal.class, grantExpiringSoon.id());
        BigDecimal permRemaining = jdbcTemplate.queryForObject(
                "SELECT remaining_amount FROM prepaid_credit_grants WHERE id = ?",
                BigDecimal.class, grantNoExpiry.id());
        BigDecimal expiredRemaining = jdbcTemplate.queryForObject(
                "SELECT remaining_amount FROM prepaid_credit_grants WHERE id = ?",
                BigDecimal.class, expiredGrant.id());

        assertThat(soonRemaining).isEqualByComparingTo("0.0000");
        assertThat(permRemaining).isEqualByComparingTo("0.0000");
        assertThat(expiredRemaining).isEqualByComparingTo("500.0000");
    }

    @Test
    void creditNotesEnforceCurrencySafetyCumulativeLimitsAndWalletRefundOnPaidInvoices() {
        CustomerSummary customer = identityManagement.createCustomer("cust-cn-usd", "CreditNote Co", SupportedCurrency.USD);
        taxPolicyFacade.configureTaxPolicy(new ConfigureTaxPolicyCommand(
                "US_10", "US 10% Tax", customer.id(), "US", "Sales Tax", new BigDecimal("10.0000"), false
        ));

        PlanVersionDetail draftPlan = planCatalog.createPlanWithDraft(
                new CreatePlanCommand("plan-cn", "CN Plan", "Flat", SupportedCurrency.USD,
                        Instant.parse("2026-01-01T00:00:00Z")));
        planCatalog.updateDraftVersion(draftPlan.id(), new UpdateDraftPlanVersionCommand(
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of(new ConfigureChargeCommand(1, "BASE", "Base Plan", ChargeTypes.FLAT_FEE,
                        null, new BigDecimal("200.0000"), null, null, null, List.of(), Map.of())),
                List.of(),
                List.of()
        ));
        PlanVersionDetail published = planCatalog.publishVersion(draftPlan.id());

        Instant p1Start = Instant.parse("2026-02-01T00:00:00Z");
        Instant p1End = Instant.parse("2026-03-01T00:00:00Z");
        SubscriptionSummary sub = subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                customer.id(), published.id(), SupportedCurrency.USD, BillingInterval.MONTHLY,
                "UTC", 1, p1Start, null, null, "sub-cn-1"
        ));

        InvoiceDetail draft = invoiceManagement.generateOrRefreshDraft(
                new GenerateDraftInvoiceCommand(sub.id(), p1Start, p1End, 0, null, "draft-cn-1"));

        // Credit note on DRAFT invoice must be rejected
        assertThatThrownBy(() -> creditNoteFacade.issueCreditNote(new IssueCreditNoteCommand(
                draft.invoice().id(), SupportedCurrency.USD, CreditNoteType.PARTIAL, "Too early",
                new BigDecimal("10.0000"), List.of(), "cn-draft-fail"
        ))).isInstanceOf(InvalidInvoiceTransitionException.class);

        // Finalize -> 200 subtotal + 20 tax = 220 total due
        InvoiceDetail finalized = invoiceManagement.finalizeInvoice(
                draft.invoice().id(), new FinalizeInvoiceCommand(false, null, "fin-cn-1"));
        assertThat(finalized.invoice().totalAmount()).isEqualTo(Money.usd("220.0000"));
        assertThat(finalized.invoice().amountDue()).isEqualTo(Money.usd("220.0000"));

        // Currency mismatch rejected
        assertThatThrownBy(() -> creditNoteFacade.issueCreditNote(new IssueCreditNoteCommand(
                finalized.invoice().id(), SupportedCurrency.BDT, CreditNoteType.PARTIAL, "Wrong currency",
                new BigDecimal("50.0000"), List.of(), "cn-curr-fail"
        ))).isInstanceOf(CurrencyMismatchException.class);

        // Partial credit note: 50 subtotal + 5 proportional tax = 55 total credit
        UUID lineId = finalized.lines().getFirst().id();
        CreditNoteDetail partialCn = creditNoteFacade.issueCreditNote(new IssueCreditNoteCommand(
                finalized.invoice().id(),
                SupportedCurrency.USD,
                CreditNoteType.PARTIAL,
                "Service outage credit",
                null,
                List.of(new CreditNoteLineInput(lineId, "SLA credit", new BigDecimal("50.0000"))),
                "cn-partial-1"
        ));
        assertThat(partialCn.creditNoteNumber()).startsWith("CN-");
        assertThat(partialCn.subtotalCredit()).isEqualTo(Money.usd("50.0000"));
        assertThat(partialCn.taxCredit()).isEqualTo(Money.usd("5.0000"));
        assertThat(partialCn.totalCredit()).isEqualTo(Money.usd("55.0000"));
        assertThat(partialCn.appliedToInvoiceDue()).isEqualTo(Money.usd("55.0000"));
        assertThat(partialCn.refundedToCreditWallet()).isEqualTo(Money.usd("0.0000"));

        InvoiceDetail afterPartial = invoiceManagement.getInvoiceDetail(finalized.invoice().id());
        assertThat(afterPartial.invoice().totalAmount()).isEqualTo(Money.usd("220.0000"));
        assertThat(afterPartial.invoice().amountDue()).isEqualTo(Money.usd("165.0000"));
        assertThat(afterPartial.invoice().creditNotesAppliedAmount()).isEqualTo(Money.usd("55.0000"));

        // Over-limit credit note rejected (remaining creditable is 165, attempting 200+20=220)
        assertThatThrownBy(() -> creditNoteFacade.issueCreditNote(new IssueCreditNoteCommand(
                finalized.invoice().id(), SupportedCurrency.USD, CreditNoteType.PARTIAL, "Excessive",
                new BigDecimal("200.0000"), List.of(), "cn-overlimit"
        ))).isInstanceOf(CreditNoteLimitExceededException.class);

        // Second invoice settled entirely via prepaid credit (PAID, amountDue=0) -> FULL credit note refunds wallet
        prepaidCreditFacade.issueCreditGrant(new IssueCreditGrantCommand(
                customer.id(), SupportedCurrency.USD, CreditGrantType.PREPAID, "Prepaid deposit",
                new BigDecimal("220.0000"), 100, null, "grant-paid-inv"
        ));
        Instant p2Start = p1End;
        Instant p2End = Instant.parse("2026-04-01T00:00:00Z");
        InvoiceDetail draft2 = invoiceManagement.generateOrRefreshDraft(
                new GenerateDraftInvoiceCommand(sub.id(), p2Start, p2End, 0, null, "draft-cn-2"));
        InvoiceDetail paidInvoice = invoiceManagement.finalizeInvoice(
                draft2.invoice().id(), new FinalizeInvoiceCommand(true, null, "fin-cn-2"));
        assertThat(paidInvoice.invoice().status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(paidInvoice.invoice().amountDue()).isEqualTo(Money.usd("0.0000"));

        CreditNoteDetail fullRefundCn = creditNoteFacade.issueCreditNote(new IssueCreditNoteCommand(
                paidInvoice.invoice().id(), SupportedCurrency.USD, CreditNoteType.FULL,
                "Full cancellation refund", null, List.of(), "cn-full-paid"
        ));
        assertThat(fullRefundCn.totalCredit()).isEqualTo(Money.usd("220.0000"));
        assertThat(fullRefundCn.appliedToInvoiceDue()).isEqualTo(Money.usd("0.0000"));
        assertThat(fullRefundCn.refundedToCreditWallet()).isEqualTo(Money.usd("220.0000"));

        CustomerCreditBalance refundedBalance = prepaidCreditFacade.getCustomerCreditBalance(customer.id(), SupportedCurrency.USD);
        assertThat(refundedBalance.availableBalance()).isEqualTo(Money.usd("220.0000"));

        Integer cnOutboxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT serialized_event) FROM event_publication WHERE event_type LIKE '%CreditNoteIssuedEvent'",
                Integer.class
        );
        assertThat(cnOutboxCount).isEqualTo(2);
    }

    @Test
    void lateUsageAdjustmentsAndMidCycleProrationLinesFlowDeterministicallyIntoNextInvoice() {
        CustomerSummary customer = identityManagement.createCustomer("cust-late-bdt", "Late Usage Co", SupportedCurrency.BDT);
        BillableMetricDefinition metric = metricCatalog.createMetric(new CreateMetricCommand(
                "requests_late", "Requests", "Per-request usage",
                MetricAggregationType.SUM, "units", customer.id(), List.of()
        ));

        PlanVersionDetail starterDraft = planCatalog.createPlanWithDraft(
                new CreatePlanCommand("starter-late", "Starter", "Base + Per Unit", SupportedCurrency.BDT,
                        Instant.parse("2026-01-01T00:00:00Z")));
        planCatalog.updateDraftVersion(starterDraft.id(), new UpdateDraftPlanVersionCommand(
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of(
                        new ConfigureChargeCommand(1, "BASE", "Starter Flat", ChargeTypes.FLAT_FEE,
                                null, new BigDecimal("100.0000"), null, null, null, List.of(), Map.of()),
                        new ConfigureChargeCommand(2, "REQ", "Request Charge", ChargeTypes.PER_UNIT,
                                metric.id(), null, new BigDecimal("2.0000"), null, null, List.of(), Map.of())
                ),
                List.of(),
                List.of()
        ));
        PlanVersionDetail starter = planCatalog.publishVersion(starterDraft.id());

        PlanVersionDetail growthDraft = planCatalog.createPlanWithDraft(
                new CreatePlanCommand("growth-late", "Growth", "Higher Base + Per Unit", SupportedCurrency.BDT,
                        Instant.parse("2026-01-01T00:00:00Z")));
        planCatalog.updateDraftVersion(growthDraft.id(), new UpdateDraftPlanVersionCommand(
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of(
                        new ConfigureChargeCommand(1, "BASE", "Growth Flat", ChargeTypes.FLAT_FEE,
                                null, new BigDecimal("300.0000"), null, null, null, List.of(), Map.of()),
                        new ConfigureChargeCommand(2, "REQ", "Request Charge", ChargeTypes.PER_UNIT,
                                metric.id(), null, new BigDecimal("2.0000"), null, null, List.of(), Map.of())
                ),
                List.of(),
                List.of()
        ));
        PlanVersionDetail growth = planCatalog.publishVersion(growthDraft.id());

        // Subscription Period 1 starts ~30 days ago; ingest on-time usage while Period 1 is open, then roll to Period 2
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant p1End = now.minus(Duration.ofHours(2));
        Instant p1Start = p1End.minus(Duration.ofDays(30));
        Instant p2Start = p1End;
        Instant p2End = p2Start.plus(Duration.ofDays(30));

        SubscriptionSummary sub = subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                customer.id(), starter.id(), SupportedCurrency.BDT, BillingInterval.MONTHLY,
                "UTC", 1, p1Start, null, null, "sub-late-1"
        ));
        // Keep Period 1 open momentarily so the original on-time usage event is ingested cleanly
        jdbcTemplate.update(
                "UPDATE subscriptions SET current_period_start = ?, current_period_end = ? WHERE id = ?",
                Timestamp.from(p1Start), Timestamp.from(now.plus(Duration.ofHours(1))), sub.id()
        );
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        });

        EventIngestionItemResult onTimeEv = usageIngestion.ingestSingle(new IngestUsageEventCommand(
                null, customer.id(), metric.id(), sub.id(),
                new BigDecimal("100"), now.minus(Duration.ofHours(4)), "on-time-p1", Map.of("units", 100)
        ));
        assertThat(onTimeEv.outcome()).isEqualTo(EventIngestionOutcome.ACCEPTED);

        // Advance subscription current period to Period 2 so Metering classifies subsequent Period 1 events as late
        jdbcTemplate.update(
                "UPDATE subscriptions SET current_period_start = ?, current_period_end = ? WHERE id = ?",
                Timestamp.from(p2Start), Timestamp.from(p2End), sub.id()
        );
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        });

        InvoiceDetail p1Draft = invoiceManagement.generateOrRefreshDraft(
                new GenerateDraftInvoiceCommand(sub.id(), p1Start, p1End, 0, null, "p1-draft"));
        InvoiceDetail p1Final = invoiceManagement.finalizeInvoice(
                p1Draft.invoice().id(), new FinalizeInvoiceCommand(false, null, "p1-final"));
        assertThat(p1Final.invoice().totalAmount()).isEqualTo(Money.bdt("300.0000")); // 100 flat + 100*2 usage

        // Ingest a late usage event (+50 units -> +100 BDT delta) for closed Period 1 via UsageIngestionFacade
        EventIngestionItemResult lateEv = usageIngestion.ingestSingle(new IngestUsageEventCommand(
                null, customer.id(), metric.id(), sub.id(),
                new BigDecimal("50"), p1End.minus(Duration.ofHours(1)), "late-evt-p1", Map.of("units", 50)
        ));
        assertThat(lateEv.outcome()).isEqualTo(EventIngestionOutcome.ACCEPTED_LATE);

        LateUsageProcessingSummary summary1 = billingAdjustmentFacade.processPendingLateUsage(customer.id());
        assertThat(summary1.lateRecordsEvaluated()).isEqualTo(1);
        assertThat(summary1.adjustmentsCreated()).isEqualTo(1);
        assertThat(summary1.lateRecordsMarkedConsumed()).isEqualTo(1);

        // Repeat processing -> zero duplicate adjustments
        LateUsageProcessingSummary summary2 = billingAdjustmentFacade.processPendingLateUsage(customer.id());
        assertThat(summary2.lateRecordsEvaluated()).isEqualTo(0);
        assertThat(summary2.adjustmentsCreated()).isEqualTo(0);

        List<BillingAdjustmentRecord> adjustments = billingAdjustmentFacade
                .listCustomerAdjustments(customer.id(), PageRequest.of(0, 10)).getContent();
        assertThat(adjustments).hasSize(1);
        assertThat(adjustments.getFirst().adjustmentType()).isEqualTo(BillingAdjustmentType.LATE_USAGE);
        assertThat(adjustments.getFirst().originalInvoiceId()).isEqualTo(p1Final.invoice().id());
        assertThat(adjustments.getFirst().amount()).isEqualTo(Money.bdt("100.0000"));
        assertThat(adjustments.getFirst().status()).isEqualTo(BillingAdjustmentStatus.PENDING);

        // Trigger a mid-cycle upgrade during Period 2 so a proration record is generated
        subscriptionManagement.changePlanImmediately(sub.id(), new ImmediatePlanChangeCommand(
                growth.id(), 1, p2Start.plus(Duration.ofDays(15)), "upgrade-p2"
        ));

        // Generate Period 2 draft -> must include both PRORATION_ADJUSTMENT and LATE_USAGE_ADJUSTMENT lines
        InvoiceDetail p2Draft = invoiceManagement.generateOrRefreshDraft(
                new GenerateDraftInvoiceCommand(sub.id(), p2Start, p2End, 0, null, "p2-draft"));
        assertThat(p2Draft.lines())
                .extracting(l -> l.lineType())
                .contains(InvoiceLineType.LATE_USAGE_ADJUSTMENT, InvoiceLineType.PRORATION_ADJUSTMENT);

        InvoiceDetail p2Final = invoiceManagement.finalizeInvoice(
                p2Draft.invoice().id(), new FinalizeInvoiceCommand(false, null, "p2-final"));
        assertThat(p2Final.invoice().status()).isEqualTo(InvoiceStatus.FINALIZED);

        // Adjustment must now be marked BILLED against Period 2 invoice
        List<BillingAdjustmentRecord> billedAdjustments = billingAdjustmentFacade
                .listCustomerAdjustments(customer.id(), PageRequest.of(0, 10)).getContent();
        assertThat(billedAdjustments.getFirst().status()).isEqualTo(BillingAdjustmentStatus.BILLED);
        assertThat(billedAdjustments.getFirst().appliedInvoiceId()).isEqualTo(p2Final.invoice().id());
    }

    @Test
    void springBatchBillingRunsAreIdempotentAndRestEndpointsEnforceRbacAndCustomerIsolation() throws Exception {
        CustomerSummary custA = identityManagement.createCustomer("batch-cust-a", "Batch Customer A", SupportedCurrency.USD);
        CustomerSummary custB = identityManagement.createCustomer("batch-cust-b", "Batch Customer B", SupportedCurrency.USD);

        UserSummary operator = identityManagement.createUser(
                "billing-op@meterflow.local", "Billing Operator", "OpPass123456!", PlatformRole.OPERATOR);
        UserSummary userA = identityManagement.createUser(
                "user-a@meterflow.local", "Customer A User", "UserPass123456!", PlatformRole.CUSTOMER);
        identityManagement.assignMembership(userA.id(), custA.id(), MembershipRole.OWNER);
        assertThat(operator.email()).isEqualTo("billing-op@meterflow.local");

        PlanVersionDetail draftPlan = planCatalog.createPlanWithDraft(
                new CreatePlanCommand("batch-plan", "Batch Plan", "Flat", SupportedCurrency.USD,
                        Instant.parse("2026-01-01T00:00:00Z")));
        planCatalog.updateDraftVersion(draftPlan.id(), new UpdateDraftPlanVersionCommand(
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of(new ConfigureChargeCommand(1, "BASE", "Monthly Fee", ChargeTypes.FLAT_FEE,
                        null, new BigDecimal("75.0000"), null, null, null, List.of(), Map.of())),
                List.of(),
                List.of()
        ));
        PlanVersionDetail published = planCatalog.publishVersion(draftPlan.id());

        Instant start = Instant.parse("2026-02-01T00:00:00Z");
        subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                custA.id(), published.id(), SupportedCurrency.USD, BillingInterval.MONTHLY,
                "UTC", 1, start, null, null, "batch-sub-a"
        ));
        subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                custB.id(), published.id(), SupportedCurrency.USD, BillingInterval.MONTHLY,
                "UTC", 1, start, null, null, "batch-sub-b"
        ));

        // 1. Draft generation batch run via Spring Batch 6
        Instant draftCutoff = Instant.parse("2026-03-02T00:00:00Z");
        BillingRunSummary draftRun = billingRunFacade.runDraftGenerationBatch(
                new BillingRunCommand(null, draftCutoff, 24, "run-draft-1"));
        assertThat(draftRun.status()).isEqualTo(BillingRunStatus.COMPLETED);
        assertThat(draftRun.batchJobExecutionId()).isNotNull();
        assertThat(draftRun.createdCount()).isEqualTo(2);

        String batchStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM batch_job_execution WHERE job_execution_id = ?",
                String.class, draftRun.batchJobExecutionId());
        assertThat(batchStatus).isEqualTo("COMPLETED");

        // 2. Finalization batch run past 24h grace period
        Instant finalizeCutoff = Instant.parse("2026-03-05T00:00:00Z");
        BillingRunSummary finalizeRun = billingRunFacade.runFinalizationBatch(
                new BillingRunCommand(null, finalizeCutoff, null, "run-final-1"));
        assertThat(finalizeRun.status()).isEqualTo(BillingRunStatus.COMPLETED);
        assertThat(finalizeRun.createdCount()).isEqualTo(2);

        // 3. Re-running draft and finalization batches must produce zero duplicate invoices
        BillingRunSummary rerunDraft = billingRunFacade.runDraftGenerationBatch(
                new BillingRunCommand(null, draftCutoff, 24, "run-draft-2"));
        assertThat(rerunDraft.status()).isEqualTo(BillingRunStatus.COMPLETED);
        assertThat(rerunDraft.createdCount()).isEqualTo(0);
        assertThat(rerunDraft.skippedCount()).isEqualTo(2);

        BillingRunSummary rerunFinalize = billingRunFacade.runFinalizationBatch(
                new BillingRunCommand(null, finalizeCutoff, null, "run-final-2"));
        assertThat(rerunFinalize.status()).isEqualTo(BillingRunStatus.COMPLETED);
        assertThat(rerunFinalize.createdCount()).isEqualTo(0);

        Integer invoiceCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoices", Integer.class);
        assertThat(invoiceCount).isEqualTo(2);

        UUID invoiceAId = invoiceManagement.listCustomerInvoices(custA.id(), InvoiceStatus.FINALIZED, PageRequest.of(0, 5))
                .getContent().getFirst().id();
        UUID invoiceBId = invoiceManagement.listCustomerInvoices(custB.id(), InvoiceStatus.FINALIZED, PageRequest.of(0, 5))
                .getContent().getFirst().id();

        // 4. REST RBAC & strict customer isolation checks
        mockMvc.perform(get("/api/v1/customer/" + custA.id() + "/billing/invoices")
                        .with(httpBasic("user-a@meterflow.local", "UserPass123456!")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value("FINALIZED"));

        mockMvc.perform(get("/api/v1/customer/" + custA.id() + "/billing/invoices/" + invoiceAId)
                        .with(httpBasic("user-a@meterflow.local", "UserPass123456!")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoice.totalAmount.amount").value("75.0000"));

        // Customer A cannot access Customer B's invoice list or invoice detail
        mockMvc.perform(get("/api/v1/customer/" + custB.id() + "/billing/invoices")
                        .with(httpBasic("user-a@meterflow.local", "UserPass123456!")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:meterflow:error:access-denied"));

        mockMvc.perform(get("/api/v1/customer/" + custA.id() + "/billing/invoices/" + invoiceBId)
                        .with(httpBasic("user-a@meterflow.local", "UserPass123456!")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:meterflow:error:access-denied"));

        // Customer A cannot invoke operator billing endpoints
        mockMvc.perform(post("/api/v1/billing/runs/draft")
                        .with(httpBasic("user-a@meterflow.local", "UserPass123456!"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        // Operator can list invoices and issue a partial credit note via REST
        mockMvc.perform(post("/api/v1/billing/credit-notes")
                        .with(httpBasic("billing-op@meterflow.local", "OpPass123456!"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "originalInvoiceId": "%s",
                                  "currency": "USD",
                                  "creditType": "PARTIAL",
                                  "reason": "Goodwill credit",
                                  "subtotalCreditOverride": 15.00,
                                  "idempotencyKey": "rest-cn-1"
                                }
                                """.formatted(invoiceAId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalCredit.amount").value("15.0000"));

        // Customer A can inspect their credit note via customer-scoped endpoint
        mockMvc.perform(get("/api/v1/customer/" + custA.id() + "/billing/credit-notes")
                        .with(httpBasic("user-a@meterflow.local", "UserPass123456!")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void annualTimezoneBillingVolumePackageMinimumCommitmentVoidAndManualAdjustmentsWorkEndToEnd() {
        CustomerSummary customer = identityManagement.createCustomer("cust-annual-dhaka", "Dhaka Enterprise", SupportedCurrency.BDT);
        BillableMetricDefinition volMetric = metricCatalog.createMetric(new CreateMetricCommand(
                "vol_requests", "Volume Requests", "Volume tiered requests",
                MetricAggregationType.SUM, "units", customer.id(), List.of()
        ));
        BillableMetricDefinition pkgMetric = metricCatalog.createMetric(new CreateMetricCommand(
                "sms_packets", "SMS Packets", "Packaged SMS",
                MetricAggregationType.SUM, "units", customer.id(), List.of()
        ));

        PlanVersionDetail draftPlan = planCatalog.createPlanWithDraft(
                new CreatePlanCommand("annual-ent-bdt", "Annual Enterprise", "Volume + Package + Min Commit",
                        SupportedCurrency.BDT, Instant.parse("2026-01-01T00:00:00Z")));
        planCatalog.updateDraftVersion(draftPlan.id(), new UpdateDraftPlanVersionCommand(
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of(
                        new ConfigureChargeCommand(1, "VOL_API", "Volume Tiered API", ChargeTypes.VOLUME_TIERS,
                                volMetric.id(), null, null, null, null,
                                List.of(
                                        new ChargeTierDefinition(1, BigDecimal.ZERO, new BigDecimal("100"),
                                                new BigDecimal("2.0000"), BigDecimal.ZERO, "Up to 100"),
                                        new ChargeTierDefinition(2, new BigDecimal("100"), null,
                                                new BigDecimal("1.5000"), BigDecimal.ZERO, "Over 100")
                                ), Map.of()),
                        new ConfigureChargeCommand(2, "SMS_PKG", "SMS 50-pack", ChargeTypes.PACKAGE,
                                pkgMetric.id(), null, null, new BigDecimal("50"), new BigDecimal("100.0000"),
                                List.of(), Map.of())
                ),
                List.of(),
                List.of(new ConfigureCommitmentCommand(null, Money.bdt("1000.0000")))
        ));
        PlanVersionDetail published = planCatalog.publishVersion(draftPlan.id());

        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant annualStart = now.minus(Duration.ofDays(5));
        SubscriptionSummary sub = subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                customer.id(), published.id(), SupportedCurrency.BDT, BillingInterval.ANNUAL,
                "Asia/Dhaka", 1, annualStart, null, null, "sub-annual-1"
        ));

        // 120 volume units -> tier 2 applies to all 120 units @ 1.50 = 180.0000 BDT
        assertThat(usageIngestion.ingestSingle(new IngestUsageEventCommand(
                null, customer.id(), volMetric.id(), sub.id(),
                new BigDecimal("120"), now.minus(Duration.ofHours(2)), "vol-1", Map.of("units", 120)
        )).outcome()).isEqualTo(EventIngestionOutcome.ACCEPTED);

        // 101 SMS -> ceil(101 / 50) = 3 packages @ 100.00 = 300.0000 BDT
        assertThat(usageIngestion.ingestSingle(new IngestUsageEventCommand(
                null, customer.id(), pkgMetric.id(), sub.id(),
                new BigDecimal("101"), now.minus(Duration.ofHours(1)), "pkg-1", Map.of("units", 101)
        )).outcome()).isEqualTo(EventIngestionOutcome.ACCEPTED);

        // Manual debit +250.00 and manual credit -50.00
        BillingAdjustmentRecord manualDebit = billingAdjustmentFacade.createManualAdjustment(
                new CreateManualAdjustmentCommand(customer.id(), sub.id(), null,
                        BillingAdjustmentType.MANUAL_DEBIT, "Onboarding fee", new BigDecimal("250.00"),
                        SupportedCurrency.BDT, "man-deb-1"));
        BillingAdjustmentRecord manualCredit = billingAdjustmentFacade.createManualAdjustment(
                new CreateManualAdjustmentCommand(customer.id(), sub.id(), null,
                        BillingAdjustmentType.MANUAL_CREDIT, "Loyalty discount", new BigDecimal("50.00"),
                        SupportedCurrency.BDT, "man-cred-1"));
        assertThat(manualDebit.amount()).isEqualTo(Money.bdt("250.0000"));
        assertThat(manualCredit.amount()).isEqualTo(Money.bdt("-50.0000"));

        // Rated usage = 180 + 300 = 480; plan minimum commitment = 1000 -> true-up = 520; +250 -50 manual = 1200.0000 BDT
        InvoiceDetail draft = invoiceManagement.generateOrRefreshDraft(
                new GenerateDraftInvoiceCommand(sub.id(), sub.currentPeriodStart(), sub.currentPeriodEnd(), 24, null, "ann-draft-1"));
        assertThat(draft.invoice().billingTimezone()).isEqualTo("Asia/Dhaka");
        assertThat(Duration.between(draft.invoice().periodStart(), draft.invoice().periodEnd()).toDays()).isBetween(365L, 366L);
        assertThat(draft.lines()).extracting(l -> l.lineType())
                .contains(InvoiceLineType.USAGE_CHARGE, InvoiceLineType.MINIMUM_COMMITMENT_TRUE_UP, InvoiceLineType.MANUAL_ADJUSTMENT);
        assertThat(draft.invoice().subtotalAmount()).isEqualTo(Money.bdt("1200.0000"));
        assertThat(draft.invoice().totalAmount()).isEqualTo(Money.bdt("1200.0000"));

        // Voiding DRAFT must release manual adjustments back to PENDING and allow regenerating a new draft for the same period
        InvoiceDetail voided = invoiceManagement.voidInvoice(draft.invoice().id(), new VoidInvoiceCommand("Rebuild draft", "void-1"));
        assertThat(voided.invoice().status()).isEqualTo(InvoiceStatus.VOID);

        List<BillingAdjustmentRecord> releasedAdjustments = billingAdjustmentFacade
                .listCustomerAdjustments(customer.id(), PageRequest.of(0, 10)).getContent();
        assertThat(releasedAdjustments).allSatisfy(a -> assertThat(a.status()).isEqualTo(BillingAdjustmentStatus.PENDING));

        // Regenerate clean draft for the same period -> gets new invoice ID and re-attaches both adjustments
        InvoiceDetail regenerated = invoiceManagement.generateOrRefreshDraft(
                new GenerateDraftInvoiceCommand(sub.id(), sub.currentPeriodStart(), sub.currentPeriodEnd(), 24, null, "ann-draft-2"));
        assertThat(regenerated.invoice().id()).isNotEqualTo(draft.invoice().id());
        assertThat(regenerated.invoice().totalAmount()).isEqualTo(Money.bdt("1200.0000"));

        // Pay via prepaid credits -> PAID invoice cannot be voided
        prepaidCreditFacade.issueCreditGrant(new IssueCreditGrantCommand(
                customer.id(), SupportedCurrency.BDT, CreditGrantType.PREPAID, "Annual deposit",
                new BigDecimal("1200.0000"), 100, null, "ann-grant-1"));
        InvoiceDetail paid = invoiceManagement.finalizeInvoice(
                regenerated.invoice().id(), new FinalizeInvoiceCommand(true, null, "ann-fin-1"));
        assertThat(paid.invoice().status()).isEqualTo(InvoiceStatus.PAID);

        assertThatThrownBy(() -> invoiceManagement.voidInvoice(
                paid.invoice().id(), new VoidInvoiceCommand("Illegal void", "void-fail")))
                .isInstanceOf(InvalidInvoiceTransitionException.class);
    }

    @Test
    void concurrentDraftGenerationOnSameSubscriptionAndPeriodProducesStrictlyOneInvoice() throws Exception {
        CustomerSummary customer = identityManagement.createCustomer("cust-conc-draft", "Concurrent Draft Co", SupportedCurrency.USD);
        PlanVersionDetail draftPlan = planCatalog.createPlanWithDraft(
                new CreatePlanCommand("conc-draft-plan", "Conc Plan", "Flat", SupportedCurrency.USD,
                        Instant.parse("2026-01-01T00:00:00Z")));
        planCatalog.updateDraftVersion(draftPlan.id(), new UpdateDraftPlanVersionCommand(
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of(new ConfigureChargeCommand(1, "FLAT", "Monthly Flat", ChargeTypes.FLAT_FEE,
                        null, new BigDecimal("99.0000"), null, null, null, List.of(), Map.of())),
                List.of(),
                List.of()
        ));
        PlanVersionDetail published = planCatalog.publishVersion(draftPlan.id());

        Instant pStart = Instant.parse("2026-03-01T00:00:00Z");
        Instant pEnd = Instant.parse("2026-04-01T00:00:00Z");
        SubscriptionSummary sub = subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                customer.id(), published.id(), SupportedCurrency.USD, BillingInterval.MONTHLY,
                "UTC", 1, pStart, null, null, "sub-conc-draft"
        ));

        int threads = 6;
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<InvoiceDetail>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return invoiceManagement.generateOrRefreshDraft(
                        new GenerateDraftInvoiceCommand(sub.id(), pStart, pEnd, 72, null, null));
            }));
        }
        gate.countDown();

        Set<UUID> returnedIds = new HashSet<>();
        for (Future<InvoiceDetail> f : futures) {
            InvoiceDetail result = f.get(30, TimeUnit.SECONDS);
            returnedIds.add(result.invoice().id());
            assertThat(result.invoice().totalAmount()).isEqualTo(Money.usd("99.0000"));
        }
        pool.shutdown();

        assertThat(returnedIds).hasSize(1);
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoices WHERE subscription_id = ?",
                Integer.class, sub.id());
        assertThat(count).isEqualTo(1);
    }

    @Test
    void customerScopedBatchAndInterruptedSpringBatchResumeAvoidDuplicateBillingAndDoubleCreditSpend() {
        CustomerSummary custA = identityManagement.createCustomer("resume-cust-a", "Resume A", SupportedCurrency.USD);
        CustomerSummary custB = identityManagement.createCustomer("resume-cust-b", "Resume B", SupportedCurrency.USD);

        PlanVersionDetail draftPlan = planCatalog.createPlanWithDraft(
                new CreatePlanCommand("resume-plan", "Resume Plan", "Flat 50", SupportedCurrency.USD,
                        Instant.parse("2026-01-01T00:00:00Z")));
        planCatalog.updateDraftVersion(draftPlan.id(), new UpdateDraftPlanVersionCommand(
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of(new ConfigureChargeCommand(1, "BASE", "Monthly Fee", ChargeTypes.FLAT_FEE,
                        null, new BigDecimal("50.0000"), null, null, null, List.of(), Map.of())),
                List.of(),
                List.of()
        ));
        PlanVersionDetail published = planCatalog.publishVersion(draftPlan.id());

        Instant pStart = Instant.parse("2026-02-01T00:00:00Z");
        Instant pEnd = Instant.parse("2026-03-01T00:00:00Z");
        SubscriptionSummary subA = subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                custA.id(), published.id(), SupportedCurrency.USD, BillingInterval.MONTHLY,
                "UTC", 1, pStart, null, null, "resume-sub-a"
        ));
        subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                custB.id(), published.id(), SupportedCurrency.USD, BillingInterval.MONTHLY,
                "UTC", 1, pStart, null, null, "resume-sub-b"
        ));

        // 1. Customer-scoped draft batch run bills ONLY Customer A, leaving Customer B untouched
        Instant cutoff = Instant.parse("2026-03-02T00:00:00Z");
        BillingRunSummary custARun = billingRunFacade.runDraftGenerationBatch(
                new BillingRunCommand(custA.id(), cutoff, 0, "run-cust-a-only"));
        assertThat(custARun.status()).isEqualTo(BillingRunStatus.COMPLETED);
        assertThat(custARun.createdCount()).isEqualTo(1);
        assertThat(invoiceManagement.listCustomerInvoices(custA.id(), null, PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
        assertThat(invoiceManagement.listCustomerInvoices(custB.id(), null, PageRequest.of(0, 10)).getTotalElements()).isEqualTo(0);

        // 2. Simulate an interrupted finalization run for Customer A with 2 invoices (50 USD each) and 100 USD credit
        prepaidCreditFacade.issueCreditGrant(new IssueCreditGrantCommand(
                custA.id(), SupportedCurrency.USD, CreditGrantType.PREPAID, "Prepaid 100",
                new BigDecimal("100.0000"), 100, null, "resume-credit-100"
        ));

        InvoiceDetail inv1Draft = invoiceManagement.listCustomerInvoices(custA.id(), InvoiceStatus.DRAFT, PageRequest.of(0, 5))
                .getContent().stream().findFirst()
                .map(s -> invoiceManagement.getInvoiceDetail(s.id())).orElseThrow();
        InvoiceDetail inv2Draft = invoiceManagement.generateOrRefreshDraft(new GenerateDraftInvoiceCommand(
                subA.id(), pEnd, Instant.parse("2026-04-01T00:00:00Z"), 0, null, "resume-draft-2"));

        // Invoice 1 finalized BEFORE the simulated interruption (consumes 50.00 USD); Invoice 2 still DRAFT
        InvoiceDetail inv1Final = invoiceManagement.finalizeInvoice(
                inv1Draft.invoice().id(), new FinalizeInvoiceCommand(true, null, "resume-fin-1"));
        assertThat(inv1Final.invoice().status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(inv1Final.invoice().creditsAppliedAmount()).isEqualTo(Money.usd("50.0000"));

        // Persist a genuine FAILED Spring Batch JobExecution + FAILED billing_runs record
        UUID failedRunId = UUID.randomUUID();
        Instant finalizeCutoff = Instant.parse("2026-04-05T00:00:00Z");
        JobParameters failedParams = new JobParametersBuilder()
                .addString("billingRunId", failedRunId.toString())
                .addString("cutoffTimestamp", finalizeCutoff.toString())
                .addString("customerId", custA.id().toString())
                .toJobParameters();
        JobInstance instance = jobRepository.createJobInstance("finalizeInvoicesJob", failedParams);
        JobExecution failedExec = jobRepository.createJobExecution(instance, failedParams, new ExecutionContext());
        failedExec.setStatus(BatchStatus.FAILED);
        failedExec.setExitStatus(ExitStatus.FAILED.addExitDescription("Simulated mid-run crash"));
        failedExec.setEndTime(LocalDateTime.now());
        jobRepository.update(failedExec);

        jdbcTemplate.update("""
                INSERT INTO billing_runs (
                    id, batch_job_execution_id, run_type, customer_id, cutoff_timestamp,
                    idempotency_key, status, processed_count, created_count, skipped_count, failed_count,
                    error_summary, started_at, completed_at
                ) VALUES (?, ?, 'FINALIZATION', ?, ?, 'interrupted-fin-run', 'FAILED', 1, 1, 0, 1,
                          'Simulated mid-run crash', NOW(), NOW())
                """,
                failedRunId, failedExec.getId(), custA.id(), Timestamp.from(finalizeCutoff));

        // 3. Resume failed run via BillingRunFacade -> Spring Batch restarts same JobInstance and finishes Invoice 2 cleanly
        BillingRunSummary resumed = billingRunFacade.resumeFailedBillingRun(failedRunId);
        assertThat(resumed.status()).isEqualTo(BillingRunStatus.COMPLETED);
        assertThat(resumed.batchJobExecutionId()).isNotEqualTo(failedExec.getId());
        assertThat(resumed.createdCount()).isEqualTo(1);

        // Verify both executions belong to the exact same Spring Batch JobInstance
        Integer executionsForInstance = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM batch_job_execution WHERE job_instance_id = ?",
                Integer.class, instance.getInstanceId());
        assertThat(executionsForInstance).isEqualTo(2);

        // Invoice 2 is now PAID using remaining 50.00 USD; Invoice 1 untouched; credit balance strictly 0.0000 USD
        InvoiceDetail inv2After = invoiceManagement.getInvoiceDetail(inv2Draft.invoice().id());
        assertThat(inv2After.invoice().status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(inv2After.invoice().creditsAppliedAmount()).isEqualTo(Money.usd("50.0000"));
        assertThat(prepaidCreditFacade.getCustomerCreditBalance(custA.id(), SupportedCurrency.USD).availableBalance())
                .isEqualTo(Money.usd("0.0000"));

        // Total credit consumption transactions for Customer A == strictly 2 (50 + 50, zero double spend)
        Integer consumeTxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_transactions WHERE customer_id = ? AND transaction_type = 'CONSUMED'",
                Integer.class, custA.id());
        assertThat(consumeTxCount).isEqualTo(2);
    }
}
