package com.meterflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meterflow.billing.api.FinalizeInvoiceCommand;
import com.meterflow.billing.api.GenerateDraftInvoiceCommand;
import com.meterflow.billing.api.InvoiceDetail;
import com.meterflow.billing.api.InvoiceManagementFacade;
import com.meterflow.billing.api.InvoiceStatus;
import com.meterflow.catalog.api.ChargeTypes;
import com.meterflow.catalog.api.ConfigureChargeCommand;
import com.meterflow.catalog.api.CreatePlanCommand;
import com.meterflow.catalog.api.PlanCatalogFacade;
import com.meterflow.catalog.api.PlanVersionDetail;
import com.meterflow.catalog.api.UpdateDraftPlanVersionCommand;
import com.meterflow.identity.api.CustomerSummary;
import com.meterflow.identity.api.IdentityManagementFacade;
import com.meterflow.identity.api.MembershipRole;
import com.meterflow.identity.api.PlatformRole;
import com.meterflow.identity.api.UserSummary;
import com.meterflow.payments.api.DunningCaseDetail;
import com.meterflow.payments.api.DunningCaseStatus;
import com.meterflow.payments.api.DunningExecutionSummary;
import com.meterflow.payments.api.DunningManagementFacade;
import com.meterflow.payments.api.DuplicatePaymentSettlementException;
import com.meterflow.payments.api.IneligibleInvoiceForPaymentException;
import com.meterflow.payments.api.InitiatePaymentCommand;
import com.meterflow.payments.api.MockPaymentOutcome;
import com.meterflow.payments.api.PaymentDetail;
import com.meterflow.payments.api.PaymentOverpaymentException;
import com.meterflow.payments.api.PaymentProcessingFacade;
import com.meterflow.payments.api.PaymentQueryFacade;
import com.meterflow.payments.api.PaymentStatus;
import com.meterflow.shared.BillingInterval;
import com.meterflow.shared.CurrencyMismatchException;
import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import com.meterflow.subscriptions.api.CreateSubscriptionCommand;
import com.meterflow.subscriptions.api.SubscriptionHistory;
import com.meterflow.subscriptions.api.SubscriptionManagementFacade;
import com.meterflow.subscriptions.api.SubscriptionQueryFacade;
import com.meterflow.subscriptions.api.SubscriptionStatus;
import com.meterflow.subscriptions.api.SubscriptionSummary;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
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
@Import(PaymentModuleIT.PostgresContainerConfiguration.class)
class PaymentModuleIT {

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
    private PlanCatalogFacade planCatalog;

    @Autowired
    private SubscriptionManagementFacade subscriptionManagement;

    @Autowired
    private SubscriptionQueryFacade subscriptionQuery;

    @Autowired
    private InvoiceManagementFacade invoiceManagement;

    @Autowired
    private PaymentProcessingFacade paymentProcessing;

    @Autowired
    private PaymentQueryFacade paymentQuery;

    @Autowired
    private DunningManagementFacade dunningManagement;

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
                    dunning_events,
                    payment_attempts,
                    payment_transactions,
                    dunning_cases,
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
    void successfulPaymentSettlesInvoiceProtectsImmutabilityAndRejectsInvalidRequests() {
        CustomerSummary customer = identityManagement.createCustomer("pay-cust-bdt", "Dhaka Pay Ltd", SupportedCurrency.BDT);
        PlanVersionDetail plan = createFlatPlan("pay-plan-bdt", SupportedCurrency.BDT, "150.0000");
        SubscriptionSummary sub = createActiveSub(customer.id(), plan.id(), SupportedCurrency.BDT, "pay-sub-1");

        InvoiceDetail draft = invoiceManagement.generateOrRefreshDraft(new GenerateDraftInvoiceCommand(
                sub.id(), sub.currentPeriodStart(), sub.currentPeriodEnd(), 0, null, "pay-draft-1"));

        // DRAFT invoice cannot be paid
        assertThatThrownBy(() -> paymentProcessing.initiatePayment(new InitiatePaymentCommand(
                draft.invoice().id(), null, null, MockPaymentOutcome.SIMULATE_SUCCESS, "pay-draft-fail")))
                .isInstanceOf(IneligibleInvoiceForPaymentException.class);

        InvoiceDetail finalized = invoiceManagement.finalizeInvoice(
                draft.invoice().id(), new FinalizeInvoiceCommand(false, null, "pay-fin-1"));
        assertThat(finalized.invoice().amountDue()).isEqualTo(Money.bdt("150.0000"));

        // Currency mismatch and overpayment rejected
        assertThatThrownBy(() -> paymentProcessing.initiatePayment(new InitiatePaymentCommand(
                finalized.invoice().id(), new BigDecimal("150.00"), SupportedCurrency.USD,
                MockPaymentOutcome.SIMULATE_SUCCESS, "pay-curr-fail")))
                .isInstanceOf(CurrencyMismatchException.class);

        assertThatThrownBy(() -> paymentProcessing.initiatePayment(new InitiatePaymentCommand(
                finalized.invoice().id(), new BigDecimal("150.01"), SupportedCurrency.BDT,
                MockPaymentOutcome.SIMULATE_SUCCESS, "pay-over-fail")))
                .isInstanceOf(PaymentOverpaymentException.class);

        // Capture full settlement
        PaymentDetail captured = paymentProcessing.initiatePayment(new InitiatePaymentCommand(
                finalized.invoice().id(), new BigDecimal("150.00"), SupportedCurrency.BDT,
                MockPaymentOutcome.SIMULATE_SUCCESS, "pay-ok-1"));

        assertThat(captured.payment().status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(captured.payment().paymentReference()).startsWith("PAY-");
        assertThat(captured.payment().amount()).isEqualTo(Money.bdt("150.0000"));
        assertThat(captured.attempts()).hasSize(1);

        // Authoritative invoice in Billing is now PAID with 0.0000 due
        InvoiceDetail settledInvoice = invoiceManagement.getInvoiceDetail(finalized.invoice().id());
        assertThat(settledInvoice.invoice().status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(settledInvoice.invoice().amountDue()).isEqualTo(Money.bdt("0.0000"));
        assertThat(settledInvoice.invoice().payableAmountDue()).isEqualTo(Money.bdt("0.00"));

        // Idempotent replay with same key returns same captured payment
        PaymentDetail replayed = paymentProcessing.initiatePayment(new InitiatePaymentCommand(
                finalized.invoice().id(), new BigDecimal("150.00"), SupportedCurrency.BDT,
                MockPaymentOutcome.SIMULATE_SUCCESS, "pay-ok-1"));
        assertThat(replayed.payment().id()).isEqualTo(captured.payment().id());

        // Distinct payment request against already-settled invoice rejected
        assertThatThrownBy(() -> paymentProcessing.initiatePayment(new InitiatePaymentCommand(
                finalized.invoice().id(), new BigDecimal("150.00"), SupportedCurrency.BDT,
                MockPaymentOutcome.SIMULATE_SUCCESS, "pay-ok-duplicate")))
                .isInstanceOf(DuplicatePaymentSettlementException.class);

        // Spring Modulith outbox recorded PaymentSucceededEvent (distinct event count across all listeners)
        Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT serialized_event) FROM event_publication WHERE event_type LIKE '%PaymentSucceededEvent'",
                Integer.class);
        assertThat(outboxCount).isEqualTo(1);

        // PostgreSQL immutability triggers strictly protect completed payment_transactions & payment_attempts
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE payment_transactions SET amount = 1.0000 WHERE id = ?", captured.payment().id()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM payment_transactions WHERE id = ?", captured.payment().id()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM payment_attempts WHERE payment_transaction_id = ?", captured.payment().id()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void concurrentPaymentRequestsProduceStrictlyOneCapturedSettlementAndZeroDoubleSettlement() throws Exception {
        CustomerSummary customer = identityManagement.createCustomer("pay-conc-usd", "Concurrent Pay Co", SupportedCurrency.USD);
        PlanVersionDetail plan = createFlatPlan("pay-conc-plan", SupportedCurrency.USD, "90.0000");
        SubscriptionSummary sub = createActiveSub(customer.id(), plan.id(), SupportedCurrency.USD, "pay-conc-sub");

        InvoiceDetail draft = invoiceManagement.generateOrRefreshDraft(new GenerateDraftInvoiceCommand(
                sub.id(), sub.currentPeriodStart(), sub.currentPeriodEnd(), 0, null, "pay-conc-draft"));
        InvoiceDetail finalized = invoiceManagement.finalizeInvoice(
                draft.invoice().id(), new FinalizeInvoiceCommand(false, null, "pay-conc-fin"));

        int threads = 6;
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger capturedCount = new AtomicInteger();
        AtomicInteger duplicateRejectedCount = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                try {
                    PaymentDetail detail = paymentProcessing.initiatePayment(new InitiatePaymentCommand(
                            finalized.invoice().id(), new BigDecimal("90.00"), SupportedCurrency.USD,
                            MockPaymentOutcome.SIMULATE_SUCCESS, "conc-pay-" + idx));
                    if (detail.payment().status() == PaymentStatus.CAPTURED) {
                        capturedCount.incrementAndGet();
                    }
                } catch (DuplicatePaymentSettlementException ex) {
                    duplicateRejectedCount.incrementAndGet();
                }
                return null;
            }));
        }
        gate.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(capturedCount.get()).isEqualTo(1);
        assertThat(duplicateRejectedCount.get()).isEqualTo(threads - 1);

        Integer dbCaptured = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_transactions WHERE invoice_id = ? AND status = 'CAPTURED'",
                Integer.class, finalized.invoice().id());
        assertThat(dbCaptured).isEqualTo(1);

        InvoiceDetail after = invoiceManagement.getInvoiceDetail(finalized.invoice().id());
        assertThat(after.invoice().status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(after.invoice().amountDue()).isEqualTo(Money.usd("0.0000"));
    }

    @Test
    void exactDay1Day3Day7RetriesSeparateSuspensionZeroFourthRetryAndRecoveryReactivation() {
        CustomerSummary customer = identityManagement.createCustomer("dun-cust-usd", "Dunning Test Co", SupportedCurrency.USD);
        PlanVersionDetail plan = createFlatPlan("dun-plan-usd", SupportedCurrency.USD, "120.0000");
        SubscriptionSummary sub = createActiveSub(customer.id(), plan.id(), SupportedCurrency.USD, "dun-sub-1");

        InvoiceDetail draft = invoiceManagement.generateOrRefreshDraft(new GenerateDraftInvoiceCommand(
                sub.id(), sub.currentPeriodStart(), sub.currentPeriodEnd(), 0, null, "dun-draft-1"));
        InvoiceDetail finalized = invoiceManagement.finalizeInvoice(
                draft.invoice().id(), new FinalizeInvoiceCommand(false, null, "dun-fin-1"));

        // T0: Initial soft decline -> Invoice PAST_DUE, Subscription PAST_DUE, Case RETRY_SCHEDULED (+24h)
        PaymentDetail initialFail = paymentProcessing.initiatePayment(new InitiatePaymentCommand(
                finalized.invoice().id(), null, null, MockPaymentOutcome.SIMULATE_SOFT_DECLINE, "dun-init-1"));
        assertThat(initialFail.payment().status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(initialFail.payment().retryable()).isTrue();

        assertThat(invoiceManagement.getInvoiceDetail(finalized.invoice().id()).invoice().status())
                .isEqualTo(InvoiceStatus.PAST_DUE);
        assertThat(subscriptionQuery.findSubscriptionById(sub.id()).orElseThrow().status())
                .isEqualTo(SubscriptionStatus.PAST_DUE);

        DunningCaseDetail afterT0 = paymentQuery.findDunningCaseByInvoiceId(finalized.invoice().id()).orElseThrow();
        Instant t0 = afterT0.dunningCase().initialFailureAt();
        assertThat(afterT0.dunningCase().status()).isEqualTo(DunningCaseStatus.RETRY_SCHEDULED);
        assertThat(afterT0.dunningCase().retriesCompleted()).isEqualTo(0);
        assertThat(afterT0.dunningCase().nextRetryAt()).isEqualTo(t0.plus(Duration.ofHours(24)));
        assertThat(afterT0.dunningCase().suspensionAt()).isNull();

        // T0 + 23h: Nothing due yet
        DunningExecutionSummary tooEarly = dunningManagement.processDueDunning(
                t0.plus(Duration.ofHours(23)), MockPaymentOutcome.SIMULATE_SOFT_DECLINE);
        assertThat(tooEarly.retriesProcessed()).isEqualTo(0);
        assertThat(tooEarly.subscriptionsSuspended()).isEqualTo(0);

        // Day 1 (T0 + 24h): Retry 1/3 fails -> schedules Day 3 (T0 + 72h)
        DunningExecutionSummary day1 = dunningManagement.processDueDunning(
                t0.plus(Duration.ofHours(24)), MockPaymentOutcome.SIMULATE_SOFT_DECLINE);
        assertThat(day1.retriesProcessed()).isEqualTo(1);
        assertThat(day1.retriesFailed()).isEqualTo(1);
        DunningCaseDetail afterDay1 = paymentQuery.findDunningCaseByInvoiceId(finalized.invoice().id()).orElseThrow();
        assertThat(afterDay1.dunningCase().retriesCompleted()).isEqualTo(1);
        assertThat(afterDay1.dunningCase().nextRetryAt()).isEqualTo(t0.plus(Duration.ofHours(72)));

        // Day 3 (T0 + 72h): Retry 2/3 times out -> schedules Day 7 (T0 + 168h)
        DunningExecutionSummary day3 = dunningManagement.processDueDunning(
                t0.plus(Duration.ofHours(72)), MockPaymentOutcome.SIMULATE_TIMEOUT);
        assertThat(day3.retriesProcessed()).isEqualTo(1);
        assertThat(day3.retriesFailed()).isEqualTo(1);
        DunningCaseDetail afterDay3 = paymentQuery.findDunningCaseByInvoiceId(finalized.invoice().id()).orElseThrow();
        assertThat(afterDay3.dunningCase().retriesCompleted()).isEqualTo(2);
        assertThat(afterDay3.dunningCase().nextRetryAt()).isEqualTo(t0.plus(Duration.ofHours(168)));

        // Day 7 (T0 + 168h): Retry 3/3 (Final) fails -> FINAL_WARNING, nextRetryAt=NULL, suspensionAt=T0+336h
        DunningExecutionSummary day7 = dunningManagement.processDueDunning(
                t0.plus(Duration.ofHours(168)), MockPaymentOutcome.SIMULATE_SOFT_DECLINE);
        assertThat(day7.retriesProcessed()).isEqualTo(1);
        assertThat(day7.finalWarningsIssued()).isEqualTo(1);
        assertThat(day7.subscriptionsSuspended()).isEqualTo(0);

        DunningCaseDetail afterDay7 = paymentQuery.findDunningCaseByInvoiceId(finalized.invoice().id()).orElseThrow();
        assertThat(afterDay7.dunningCase().status()).isEqualTo(DunningCaseStatus.FINAL_WARNING);
        assertThat(afterDay7.dunningCase().retriesCompleted()).isEqualTo(3);
        assertThat(afterDay7.dunningCase().nextRetryAt()).isNull();
        assertThat(afterDay7.dunningCase().suspensionAt()).isEqualTo(t0.plus(Duration.ofHours(336)));
        assertThat(subscriptionQuery.findSubscriptionById(sub.id()).orElseThrow().status())
                .isEqualTo(SubscriptionStatus.PAST_DUE);

        // Day 14 (T0 + 336h): Separate suspension timer executes ZERO 4th payment retry and suspends subscription
        DunningExecutionSummary day14 = dunningManagement.processDueDunning(
                t0.plus(Duration.ofHours(336)), MockPaymentOutcome.SIMULATE_SOFT_DECLINE);
        assertThat(day14.retriesProcessed()).isEqualTo(0);
        assertThat(day14.subscriptionsSuspended()).isEqualTo(1);

        DunningCaseDetail afterSuspend = paymentQuery.findDunningCaseByInvoiceId(finalized.invoice().id()).orElseThrow();
        assertThat(afterSuspend.dunningCase().status()).isEqualTo(DunningCaseStatus.SUSPENDED);
        assertThat(afterSuspend.dunningCase().suspendedAt()).isNotNull();
        assertThat(subscriptionQuery.findSubscriptionById(sub.id()).orElseThrow().status())
                .isEqualTo(SubscriptionStatus.SUSPENDED);

        // Strictly 4 total attempts: 1 initial + 3 retries (0 4th retry on suspension)
        assertThat(afterSuspend.paymentAttempts()).hasSize(4);

        // Recovery payment while SUSPENDED -> Invoice PAID, Case RECOVERED, Subscription reactivated to ACTIVE
        PaymentDetail recovery = paymentProcessing.initiatePayment(new InitiatePaymentCommand(
                finalized.invoice().id(), null, null, MockPaymentOutcome.SIMULATE_SUCCESS, "dun-recover-1"));
        assertThat(recovery.payment().status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(invoiceManagement.getInvoiceDetail(finalized.invoice().id()).invoice().status())
                .isEqualTo(InvoiceStatus.PAID);
        assertThat(paymentQuery.findDunningCaseByInvoiceId(finalized.invoice().id()).orElseThrow().dunningCase().status())
                .isEqualTo(DunningCaseStatus.RECOVERED);
        assertThat(subscriptionQuery.findSubscriptionById(sub.id()).orElseThrow().status())
                .isEqualTo(SubscriptionStatus.ACTIVE);

        // Verify full subscription state transition audit history
        SubscriptionHistory history = subscriptionQuery.getSubscriptionHistory(sub.id());
        assertThat(history.stateTransitions())
                .extracting(t -> t.toStatus())
                .containsSubsequence(
                        SubscriptionStatus.ACTIVE,
                        SubscriptionStatus.PAST_DUE,
                        SubscriptionStatus.SUSPENDED,
                        SubscriptionStatus.ACTIVE
                );

        // Verify all 5 domain event types persisted in Spring Modulith event_publication outbox
        assertThat(countOutboxEvents("PaymentFailedEvent")).isEqualTo(4);
        assertThat(countOutboxEvents("PaymentRetryScheduledEvent")).isEqualTo(4);
        assertThat(countOutboxEvents("SubscriptionSuspendedForNonPaymentEvent")).isEqualTo(1);
        assertThat(countOutboxEvents("PaymentSucceededEvent")).isEqualTo(1);
        assertThat(countOutboxEvents("SubscriptionReactivatedAfterPaymentEvent")).isEqualTo(1);
    }

    @Test
    void hardDeclineBypassesRetriesCancelledSubscriptionNeverReactivatesAndDay1RetryRecoveryWorks() {
        // Case A: Hard decline + CANCELLED subscription protection
        CustomerSummary custA = identityManagement.createCustomer("hard-cust-a", "Hard Decline Co", SupportedCurrency.USD);
        PlanVersionDetail plan = createFlatPlan("hard-plan-usd", SupportedCurrency.USD, "60.0000");
        SubscriptionSummary subA = createActiveSub(custA.id(), plan.id(), SupportedCurrency.USD, "hard-sub-a");
        InvoiceDetail draftA = invoiceManagement.generateOrRefreshDraft(new GenerateDraftInvoiceCommand(
                subA.id(), subA.currentPeriodStart(), subA.currentPeriodEnd(), 0, null, "hard-draft-a"));
        InvoiceDetail finA = invoiceManagement.finalizeInvoice(
                draftA.invoice().id(), new FinalizeInvoiceCommand(false, null, "hard-fin-a"));

        PaymentDetail hardFail = paymentProcessing.initiatePayment(new InitiatePaymentCommand(
                finA.invoice().id(), null, null, MockPaymentOutcome.SIMULATE_HARD_DECLINE, "hard-fail-1"));
        assertThat(hardFail.payment().retryable()).isFalse();

        DunningCaseDetail caseA = paymentQuery.findDunningCaseByInvoiceId(finA.invoice().id()).orElseThrow();
        assertThat(caseA.dunningCase().status()).isEqualTo(DunningCaseStatus.FINAL_WARNING);
        assertThat(caseA.dunningCase().nextRetryAt()).isNull();
        assertThat(caseA.dunningCase().suspensionAt()).isNotNull();

        // Customer cancels subscription; later pays overdue invoice -> subscription MUST remain CANCELLED
        subscriptionManagement.cancelImmediately(subA.id(), "cancel-before-pay");
        assertThat(subscriptionQuery.findSubscriptionById(subA.id()).orElseThrow().status())
                .isEqualTo(SubscriptionStatus.CANCELLED);

        PaymentDetail lateSettlement = paymentProcessing.initiatePayment(new InitiatePaymentCommand(
                finA.invoice().id(), null, null, MockPaymentOutcome.SIMULATE_SUCCESS, "pay-after-cancel"));
        assertThat(lateSettlement.payment().status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(invoiceManagement.getInvoiceDetail(finA.invoice().id()).invoice().status())
                .isEqualTo(InvoiceStatus.PAID);
        assertThat(subscriptionQuery.findSubscriptionById(subA.id()).orElseThrow().status())
                .isEqualTo(SubscriptionStatus.CANCELLED);

        // Case B: Retryable soft decline recovers cleanly on Day 1 automated retry
        CustomerSummary custB = identityManagement.createCustomer("retry-rec-b", "Retry Recovery Co", SupportedCurrency.USD);
        SubscriptionSummary subB = createActiveSub(custB.id(), plan.id(), SupportedCurrency.USD, "rec-sub-b");
        InvoiceDetail draftB = invoiceManagement.generateOrRefreshDraft(new GenerateDraftInvoiceCommand(
                subB.id(), subB.currentPeriodStart(), subB.currentPeriodEnd(), 0, null, "rec-draft-b"));
        InvoiceDetail finB = invoiceManagement.finalizeInvoice(
                draftB.invoice().id(), new FinalizeInvoiceCommand(false, null, "rec-fin-b"));

        paymentProcessing.initiatePayment(new InitiatePaymentCommand(
                finB.invoice().id(), null, null, MockPaymentOutcome.SIMULATE_SOFT_DECLINE, "rec-fail-b"));
        DunningCaseDetail caseB = paymentQuery.findDunningCaseByInvoiceId(finB.invoice().id()).orElseThrow();

        DunningExecutionSummary recoveredRun = dunningManagement.processDueDunning(
                caseB.dunningCase().nextRetryAt(), MockPaymentOutcome.SIMULATE_SUCCESS);
        assertThat(recoveredRun.retriesProcessed()).isEqualTo(1);
        assertThat(recoveredRun.retriesSucceeded()).isEqualTo(1);
        assertThat(invoiceManagement.getInvoiceDetail(finB.invoice().id()).invoice().status())
                .isEqualTo(InvoiceStatus.PAID);
        assertThat(subscriptionQuery.findSubscriptionById(subB.id()).orElseThrow().status())
                .isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void concurrentDunningWorkersAreRestartSafeAndExecuteZeroDuplicateRetries() throws Exception {
        PlanVersionDetail plan = createFlatPlan("skip-plan-usd", SupportedCurrency.USD, "45.0000");
        List<UUID> invoiceIds = new ArrayList<>();
        Instant dueAt = null;

        for (int i = 0; i < 3; i++) {
            CustomerSummary c = identityManagement.createCustomer("skip-cust-" + i, "Skip Co " + i, SupportedCurrency.USD);
            SubscriptionSummary s = createActiveSub(c.id(), plan.id(), SupportedCurrency.USD, "skip-sub-" + i);
            InvoiceDetail d = invoiceManagement.generateOrRefreshDraft(new GenerateDraftInvoiceCommand(
                    s.id(), s.currentPeriodStart(), s.currentPeriodEnd(), 0, null, "skip-draft-" + i));
            InvoiceDetail f = invoiceManagement.finalizeInvoice(
                    d.invoice().id(), new FinalizeInvoiceCommand(false, null, "skip-fin-" + i));
            paymentProcessing.initiatePayment(new InitiatePaymentCommand(
                    f.invoice().id(), null, null, MockPaymentOutcome.SIMULATE_SOFT_DECLINE, "skip-init-" + i));
            invoiceIds.add(f.invoice().id());
            dueAt = paymentQuery.findDunningCaseByInvoiceId(f.invoice().id()).orElseThrow().dunningCase().nextRetryAt();
        }

        final Instant triggerTime = dueAt.plus(Duration.ofMinutes(1));
        int workerThreads = 4;
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(workerThreads);
        List<Future<DunningExecutionSummary>> futures = new ArrayList<>();
        for (int i = 0; i < workerThreads; i++) {
            futures.add(pool.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return dunningManagement.processDueDunning(triggerTime, MockPaymentOutcome.SIMULATE_SOFT_DECLINE);
            }));
        }
        gate.countDown();

        int totalRetriesProcessed = 0;
        for (Future<DunningExecutionSummary> f : futures) {
            totalRetriesProcessed += f.get(30, TimeUnit.SECONDS).retriesProcessed();
        }
        pool.shutdown();

        // Across 4 simultaneous workers, strictly 3 retries executed (1 per case)
        assertThat(totalRetriesProcessed).isEqualTo(3);
        for (UUID invId : invoiceIds) {
            DunningCaseDetail detail = paymentQuery.findDunningCaseByInvoiceId(invId).orElseThrow();
            assertThat(detail.dunningCase().retriesCompleted()).isEqualTo(1);
            assertThat(detail.paymentAttempts()).hasSize(2); // 1 initial + 1 retry
        }

        // Re-running after restart at same timestamp executes 0 duplicate retries
        DunningExecutionSummary rerun = dunningManagement.processDueDunning(
                triggerTime, MockPaymentOutcome.SIMULATE_SOFT_DECLINE);
        assertThat(rerun.retriesProcessed()).isEqualTo(0);
    }

    @Test
    void restEndpointsEnforceRbacCustomerIsolationAndBlockCustomerSimulationAccess() throws Exception {
        CustomerSummary custA = identityManagement.createCustomer("rest-pay-a", "Rest Pay A", SupportedCurrency.USD);
        CustomerSummary custB = identityManagement.createCustomer("rest-pay-b", "Rest Pay B", SupportedCurrency.USD);

        UserSummary op = identityManagement.createUser("pay-op@meterflow.local", "Payment Operator", "OpPass123456!", PlatformRole.OPERATOR);
        UserSummary userA = identityManagement.createUser("pay-user-a@meterflow.local", "Customer A", "UserPass123456!", PlatformRole.CUSTOMER);
        identityManagement.assignMembership(userA.id(), custA.id(), MembershipRole.OWNER);
        assertThat(op.email()).isEqualTo("pay-op@meterflow.local");

        PlanVersionDetail plan = createFlatPlan("rest-pay-plan", SupportedCurrency.USD, "80.0000");
        SubscriptionSummary subA = createActiveSub(custA.id(), plan.id(), SupportedCurrency.USD, "rest-sub-a");
        SubscriptionSummary subB = createActiveSub(custB.id(), plan.id(), SupportedCurrency.USD, "rest-sub-b");

        InvoiceDetail finA = invoiceManagement.finalizeInvoice(
                invoiceManagement.generateOrRefreshDraft(new GenerateDraftInvoiceCommand(
                        subA.id(), subA.currentPeriodStart(), subA.currentPeriodEnd(), 0, null, "rest-da")).invoice().id(),
                new FinalizeInvoiceCommand(false, null, "rest-fa"));
        InvoiceDetail finB = invoiceManagement.finalizeInvoice(
                invoiceManagement.generateOrRefreshDraft(new GenerateDraftInvoiceCommand(
                        subB.id(), subB.currentPeriodStart(), subB.currentPeriodEnd(), 0, null, "rest-db")).invoice().id(),
                new FinalizeInvoiceCommand(false, null, "rest-fb"));

        // Customer A MUST NOT access operator mock payment initiation or dunning trigger endpoints
        mockMvc.perform(post("/api/v1/payments")
                        .with(httpBasic("pay-user-a@meterflow.local", "UserPass123456!"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "invoiceId": "%s",
                                  "simulationOutcome": "SIMULATE_SUCCESS",
                                  "idempotencyKey": "cust-blocked"
                                }
                                """.formatted(finA.invoice().id())))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/dunning/process")
                        .with(httpBasic("pay-user-a@meterflow.local", "UserPass123456!"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        // Operator initiates failed payment for A (opening dunning case) and successful payment for B
        mockMvc.perform(post("/api/v1/payments")
                        .with(httpBasic("pay-op@meterflow.local", "OpPass123456!"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "invoiceId": "%s",
                                  "simulationOutcome": "SIMULATE_SOFT_DECLINE",
                                  "idempotencyKey": "op-pay-a-1"
                                }
                                """.formatted(finA.invoice().id())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment.status").value("FAILED"));

        mockMvc.perform(post("/api/v1/payments")
                        .with(httpBasic("pay-op@meterflow.local", "OpPass123456!"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "invoiceId": "%s",
                                  "simulationOutcome": "SIMULATE_SUCCESS",
                                  "idempotencyKey": "op-pay-b-1"
                                }
                                """.formatted(finB.invoice().id())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment.status").value("CAPTURED"));

        // Duplicate payment on already-settled Invoice B returns RFC 9457 409 Conflict
        mockMvc.perform(post("/api/v1/payments")
                        .with(httpBasic("pay-op@meterflow.local", "OpPass123456!"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "invoiceId": "%s",
                                  "simulationOutcome": "SIMULATE_SUCCESS",
                                  "idempotencyKey": "op-pay-b-dup"
                                }
                                """.formatted(finB.invoice().id())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:meterflow:error:duplicate-payment-settlement"));

        UUID paymentBId = paymentQuery.listPaymentsForInvoice(finB.invoice().id()).getFirst().id();

        // Customer A reads own payments and own dunning cases cleanly
        mockMvc.perform(get("/api/v1/customer/" + custA.id() + "/payments")
                        .with(httpBasic("pay-user-a@meterflow.local", "UserPass123456!")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));

        mockMvc.perform(get("/api/v1/customer/" + custA.id() + "/dunning/cases")
                        .with(httpBasic("pay-user-a@meterflow.local", "UserPass123456!")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("RETRY_SCHEDULED"));

        // Customer A blocked from Customer B's payments, payment detail, and dunning cases
        mockMvc.perform(get("/api/v1/customer/" + custB.id() + "/payments")
                        .with(httpBasic("pay-user-a@meterflow.local", "UserPass123456!")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:meterflow:error:access-denied"));

        mockMvc.perform(get("/api/v1/customer/" + custA.id() + "/payments/" + paymentBId)
                        .with(httpBasic("pay-user-a@meterflow.local", "UserPass123456!")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:meterflow:error:access-denied"));
    }

    private PlanVersionDetail createFlatPlan(String code, SupportedCurrency currency, String amount) {
        PlanVersionDetail draft = planCatalog.createPlanWithDraft(new CreatePlanCommand(
                code, code, "Flat recurring plan", currency, Instant.parse("2026-01-01T00:00:00Z")));
        planCatalog.updateDraftVersion(draft.id(), new UpdateDraftPlanVersionCommand(
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of(new ConfigureChargeCommand(1, "BASE", "Base Plan", ChargeTypes.FLAT_FEE,
                        null, new BigDecimal(amount), null, null, null, List.of(), Map.of())),
                List.of(),
                List.of()
        ));
        return planCatalog.publishVersion(draft.id());
    }

    private SubscriptionSummary createActiveSub(UUID customerId, UUID planVersionId, SupportedCurrency currency, String key) {
        return subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                customerId, planVersionId, currency, BillingInterval.MONTHLY,
                "UTC", 1, Instant.parse("2026-03-01T00:00:00Z"), null, null, key));
    }

    private int countOutboxEvents(String simpleName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT serialized_event) FROM event_publication WHERE event_type LIKE ?",
                Integer.class, "%" + simpleName);
        return count == null ? 0 : count;
    }
}
