package com.meterflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.meterflow.catalog.api.PlanCatalogFacade;
import com.meterflow.catalog.api.PlanVersionDetail;
import com.meterflow.catalog.api.UpdateDraftPlanVersionCommand;
import com.meterflow.identity.api.CustomerSummary;
import com.meterflow.identity.api.IdentityManagementFacade;
import com.meterflow.identity.api.MembershipRole;
import com.meterflow.identity.api.PlatformRole;
import com.meterflow.identity.api.UserSummary;
import com.meterflow.shared.BillingInterval;
import com.meterflow.shared.CacheNames;
import com.meterflow.shared.CurrencyMismatchException;
import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import com.meterflow.subscriptions.api.ConflictingSubscriptionException;
import com.meterflow.subscriptions.api.CreateSubscriptionCommand;
import com.meterflow.subscriptions.api.ImmediatePlanChangeCommand;
import com.meterflow.subscriptions.api.ImmediatePlanChangeResult;
import com.meterflow.subscriptions.api.InvalidSubscriptionRequestException;
import com.meterflow.subscriptions.api.InvalidSubscriptionTransitionException;
import com.meterflow.subscriptions.api.MaintenanceProcessingSummary;
import com.meterflow.subscriptions.api.PlanChangeDirection;
import com.meterflow.subscriptions.api.SchedulePlanChangeCommand;
import com.meterflow.subscriptions.api.ScheduledChangeStatus;
import com.meterflow.subscriptions.api.ScheduledPlanChangeRecord;
import com.meterflow.subscriptions.api.SubscriptionHistory;
import com.meterflow.subscriptions.api.SubscriptionMaintenanceFacade;
import com.meterflow.subscriptions.api.SubscriptionManagementFacade;
import com.meterflow.subscriptions.api.SubscriptionQueryFacade;
import com.meterflow.subscriptions.api.SubscriptionStatus;
import com.meterflow.subscriptions.api.SubscriptionSummary;
import com.meterflow.subscriptions.api.TrialConversionPolicy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
@Import(SubscriptionModuleIT.PostgresContainerConfiguration.class)
class SubscriptionModuleIT {

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
    private SubscriptionQueryFacade subscriptionQuery;

    @Autowired
    private SubscriptionMaintenanceFacade subscriptionMaintenance;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    }

    @Test
    void enforcesPublishedPlanOnlyCurrencySafetySingleOpenPolicyAndIdempotentCreation() {
        CustomerSummary bdtCustomer = identityManagement.createCustomer("sub-bdt-1", "BDT Customer", SupportedCurrency.BDT);
        PlanVersionDetail draftPlan = createDraftPlan("draft-bdt", SupportedCurrency.BDT, "1000.00", null, null);
        PlanVersionDetail usdPlan = createPublishedPlan("starter-usd", SupportedCurrency.USD, "50.00", null, null);
        PlanVersionDetail bdtPlan = createPublishedPlan("starter-bdt", SupportedCurrency.BDT, "1000.00", "200.00", null);

        // Reject DRAFT plan version
        assertThatThrownBy(() -> subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                bdtCustomer.id(), draftPlan.id(), SupportedCurrency.BDT, BillingInterval.MONTHLY,
                "Asia/Dhaka", 2, null, null, null, "draft-attempt"
        ))).isInstanceOf(InvalidSubscriptionRequestException.class);

        // Reject currency mismatch between customer and plan
        assertThatThrownBy(() -> subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                bdtCustomer.id(), usdPlan.id(), SupportedCurrency.BDT, BillingInterval.MONTHLY,
                "UTC", 1, null, null, null, "currency-mismatch"
        ))).isInstanceOf(CurrencyMismatchException.class);

        // Create active subscription with idempotency key
        Instant start = Instant.parse("2026-04-01T00:00:00Z");
        SubscriptionSummary created = subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                bdtCustomer.id(), bdtPlan.id(), SupportedCurrency.BDT, BillingInterval.MONTHLY,
                "Asia/Dhaka", 2, start, null, null, "create-key-1"
        ));
        assertThat(created.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(created.currentPlanVersionId()).isEqualTo(bdtPlan.id());
        assertThat(created.currentPeriodStart()).isEqualTo(start);
        assertThat(created.currentPeriodEnd()).isEqualTo(Instant.parse("2026-05-01T00:00:00Z"));

        // Replay with identical idempotency key returns identical subscription without duplicating rows
        SubscriptionSummary replay = subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                bdtCustomer.id(), bdtPlan.id(), SupportedCurrency.BDT, BillingInterval.MONTHLY,
                "Asia/Dhaka", 2, start, null, null, "create-key-1"
        ));
        assertThat(replay.id()).isEqualTo(created.id());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM subscriptions", Integer.class)).isEqualTo(1);

        // Single-open-subscription policy rejects second subscription with different key
        assertThatThrownBy(() -> subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                bdtCustomer.id(), bdtPlan.id(), SupportedCurrency.BDT, BillingInterval.MONTHLY,
                "Asia/Dhaka", 1, start, null, null, "create-key-2"
        ))).isInstanceOf(ConflictingSubscriptionException.class);

        // Cancel immediately -> customer may now open a new subscription cleanly
        SubscriptionSummary cancelled = subscriptionManagement.cancelImmediately(created.id(), "cancel-1");
        assertThat(cancelled.status()).isEqualTo(SubscriptionStatus.CANCELLED);

        SubscriptionSummary replacement = subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                bdtCustomer.id(), bdtPlan.id(), SupportedCurrency.BDT, BillingInterval.MONTHLY,
                "Asia/Dhaka", 3, null, null, null, "create-key-3"
        ));
        assertThat(replacement.id()).isNotEqualTo(created.id());
        assertThat(replacement.status()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void supportsTrialManualConversionCancellationAutoConversionAndExpiryIdempotently() {
        PlanVersionDetail plan = createPublishedPlan("trial-plan", SupportedCurrency.USD, "100.00", null, null);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        // 1. Manual conversion of an active trial retains exact plan version
        CustomerSummary cust1 = identityManagement.createCustomer("trial-c1", "Trial One", SupportedCurrency.USD);
        SubscriptionSummary trial1 = subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                cust1.id(), plan.id(), SupportedCurrency.USD, BillingInterval.MONTHLY,
                "UTC", 1, now.minus(Duration.ofDays(2)), now.plus(Duration.ofDays(12)),
                TrialConversionPolicy.EXPIRE_ON_END, "t1"
        ));
        assertThat(trial1.status()).isEqualTo(SubscriptionStatus.TRIALING);
        assertThat(trial1.currentPeriodStart()).isNull();

        SubscriptionSummary converted = subscriptionManagement.convertTrial(trial1.id(), "conv-1");
        assertThat(converted.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(converted.currentPlanVersionId()).isEqualTo(plan.id());
        assertThat(converted.currentPeriodStart()).isNotNull();
        assertThat(converted.currentPeriodEnd()).isAfter(converted.currentPeriodStart());

        // Idempotent replay of trial conversion returns current state without failing
        assertThat(subscriptionManagement.convertTrial(trial1.id(), "conv-1").status())
                .isEqualTo(SubscriptionStatus.ACTIVE);

        SubscriptionHistory history1 = subscriptionQuery.getSubscriptionHistory(trial1.id());
        assertThat(history1.planAssignments()).hasSize(2);
        assertThat(history1.planAssignments().get(0).assignmentReason()).isEqualTo("INITIAL_TRIAL");
        assertThat(history1.planAssignments().get(0).effectiveTo()).isNotNull();
        assertThat(history1.planAssignments().get(1).assignmentReason()).isEqualTo("TRIAL_CONVERSION");

        // 2. Trial cancellation
        CustomerSummary cust2 = identityManagement.createCustomer("trial-c2", "Trial Two", SupportedCurrency.USD);
        SubscriptionSummary trial2 = subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                cust2.id(), plan.id(), SupportedCurrency.USD, BillingInterval.MONTHLY,
                "UTC", 1, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(10)),
                TrialConversionPolicy.EXPIRE_ON_END, "t2"
        ));
        SubscriptionSummary cancelledTrial = subscriptionManagement.cancelTrial(trial2.id(), "cancel-t2");
        assertThat(cancelledTrial.status()).isEqualTo(SubscriptionStatus.CANCELLED);

        // 3. Automated trial expiry (EXPIRE_ON_END) + auto-conversion (AUTO_CONVERT_TO_PAID) via maintenance
        CustomerSummary cust3 = identityManagement.createCustomer("trial-c3", "Trial Expire", SupportedCurrency.USD);
        CustomerSummary cust4 = identityManagement.createCustomer("trial-c4", "Trial AutoConvert", SupportedCurrency.USD);
        Instant pastStart = now.minus(Duration.ofDays(15));
        Instant pastTrialEnd = now.minus(Duration.ofHours(1));

        SubscriptionSummary expiringTrial = subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                cust3.id(), plan.id(), SupportedCurrency.USD, BillingInterval.MONTHLY,
                "UTC", 1, pastStart, pastTrialEnd, TrialConversionPolicy.EXPIRE_ON_END, "t3"
        ));
        SubscriptionSummary autoConvertTrial = subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                cust4.id(), plan.id(), SupportedCurrency.USD, BillingInterval.MONTHLY,
                "UTC", 1, pastStart, pastTrialEnd, TrialConversionPolicy.AUTO_CONVERT_TO_PAID, "t4"
        ));

        // Manual conversion of already-expired trial must be rejected
        assertThatThrownBy(() -> subscriptionManagement.convertTrial(expiringTrial.id(), "late-conv"))
                .isInstanceOf(InvalidSubscriptionTransitionException.class);

        MaintenanceProcessingSummary firstRun = subscriptionMaintenance.processDueOperationsAt(now);
        assertThat(firstRun.trialsExpired()).isEqualTo(1);
        assertThat(firstRun.trialsConverted()).isEqualTo(1);

        assertThat(subscriptionQuery.findSubscriptionById(expiringTrial.id()).orElseThrow().status())
                .isEqualTo(SubscriptionStatus.EXPIRED);
        SubscriptionSummary autoActivated = subscriptionQuery.findSubscriptionById(autoConvertTrial.id()).orElseThrow();
        assertThat(autoActivated.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(autoActivated.currentPeriodStart()).isEqualTo(pastTrialEnd);

        // Second maintenance run is strictly idempotent (0 changes)
        MaintenanceProcessingSummary secondRun = subscriptionMaintenance.processDueOperationsAt(now);
        assertThat(secondRun).isEqualTo(new MaintenanceProcessingSummary(0, 0, 0, 0));
    }

    @Test
    void computesAndPersistsImmediateUpgradeDowngradeProrationsWithUsageExclusionAndHistory() {
        CustomerSummary customer = identityManagement.createCustomer("pro-cust", "Proration Customer", SupportedCurrency.BDT);
        BillableMetricDefinition apiMetric = metricCatalog.createMetric(new CreateMetricCommand(
                "sub_api_calls", "API Calls", null, MetricAggregationType.COUNT, null, null, List.of()
        ));

        // Starter: 1000 BDT flat + 200 BDT/seat + 2.00 BDT/unit API usage (usage excluded from proration)
        PlanVersionDetail starter = createPublishedPlan("starter-mix", SupportedCurrency.BDT, "1000.00", "200.00", apiMetric.id());
        // Enterprise: 3000 BDT flat + 500 BDT/seat + 1.00 BDT/unit API usage
        PlanVersionDetail enterprise = createPublishedPlan("enterprise-mix", SupportedCurrency.BDT, "3000.00", "500.00", apiMetric.id());

        Instant periodStart = Instant.parse("2026-04-01T00:00:00Z"); // 30-day April period
        SubscriptionSummary sub = subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                customer.id(), starter.id(), SupportedCurrency.BDT, BillingInterval.MONTHLY,
                "UTC", 2, periodStart, null, null, "sub-init"
        ));

        // Midpoint immediate UPGRADE (day 15 of 30 => factor 0.5):
        // Old recurring: 1000 + (2 * 200) = 1400 BDT -> credit 700.0000
        // New recurring: 3000 + (4 * 500) = 5000 BDT -> debit 2500.0000
        // Net proration: +1800.0000 BDT; usage charge "usage_calls" explicitly listed as non-proratable
        Instant midpoint = Instant.parse("2026-04-16T00:00:00Z");
        ImmediatePlanChangeResult upgrade = subscriptionManagement.changePlanImmediately(
                sub.id(),
                new ImmediatePlanChangeCommand(enterprise.id(), 4, midpoint, "upg-key-1")
        );

        assertThat(upgrade.subscription().currentPlanVersionId()).isEqualTo(enterprise.id());
        assertThat(upgrade.subscription().seatQuantity()).isEqualTo(4);
        assertThat(upgrade.prorationRecord().prorationFactor()).isEqualByComparingTo("0.50000000");
        assertThat(upgrade.prorationRecord().previousPlanRecurringAmount()).isEqualTo(Money.bdt("1400.0000"));
        assertThat(upgrade.prorationRecord().newPlanRecurringAmount()).isEqualTo(Money.bdt("5000.0000"));
        assertThat(upgrade.prorationRecord().creditAmount()).isEqualTo(Money.bdt("700.0000"));
        assertThat(upgrade.prorationRecord().debitAmount()).isEqualTo(Money.bdt("2500.0000"));
        assertThat(upgrade.prorationRecord().netProrationAmount()).isEqualTo(Money.bdt("1800.0000"));
        assertThat(upgrade.prorationRecord().nonProratableUsageCharges()).containsExactly("usage_calls");

        // Idempotent replay returns identical proration record without duplicating rows
        ImmediatePlanChangeResult upgradeReplay = subscriptionManagement.changePlanImmediately(
                sub.id(),
                new ImmediatePlanChangeCommand(enterprise.id(), 4, midpoint, "upg-key-1")
        );
        assertThat(upgradeReplay.prorationRecord().id()).isEqualTo(upgrade.prorationRecord().id());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM subscription_proration_records", Integer.class))
                .isEqualTo(1);

        // Immediate DOWNGRADE at day 24 of 30 (6 days left => factor 0.2):
        // Old recurring: 5000 BDT -> credit 1000.0000
        // New recurring: 1000 + (1 * 200) = 1200 BDT -> debit 240.0000
        // Net proration: -760.0000 BDT
        Instant day24 = Instant.parse("2026-04-25T00:00:00Z");
        ImmediatePlanChangeResult downgrade = subscriptionManagement.changePlanImmediately(
                sub.id(),
                new ImmediatePlanChangeCommand(starter.id(), 1, day24, "dwn-key-1")
        );
        assertThat(downgrade.prorationRecord().prorationFactor()).isEqualByComparingTo("0.20000000");
        assertThat(downgrade.prorationRecord().creditAmount()).isEqualTo(Money.bdt("1000.0000"));
        assertThat(downgrade.prorationRecord().debitAmount()).isEqualTo(Money.bdt("240.0000"));
        assertThat(downgrade.prorationRecord().netProrationAmount()).isEqualTo(Money.bdt("-760.0000"));

        // Verify immutable assignment segments & history
        SubscriptionHistory history = subscriptionQuery.getSubscriptionHistory(sub.id());
        assertThat(history.planAssignments()).hasSize(3);
        assertThat(history.planAssignments().get(0).effectiveFrom()).isEqualTo(periodStart);
        assertThat(history.planAssignments().get(0).effectiveTo()).isEqualTo(midpoint);
        assertThat(history.planAssignments().get(1).effectiveFrom()).isEqualTo(midpoint);
        assertThat(history.planAssignments().get(1).effectiveTo()).isEqualTo(day24);
        assertThat(history.planAssignments().get(2).effectiveFrom()).isEqualTo(day24);
        assertThat(history.planAssignments().get(2).effectiveTo()).isNull();
        assertThat(history.prorationRecords()).hasSize(2);
        assertThat(history.stateTransitions()).hasSize(3);
    }

    @Test
    void managesScheduledPlanChangesConflictReplacementExecutionPauseResumeAndEndOfPeriodCancel() {
        CustomerSummary customer = identityManagement.createCustomer("sched-cust", "Scheduled Cust", SupportedCurrency.USD);
        PlanVersionDetail basic = createPublishedPlan("basic-usd", SupportedCurrency.USD, "50.00", null, null);
        PlanVersionDetail growth = createPublishedPlan("growth-usd", SupportedCurrency.USD, "150.00", null, null);
        PlanVersionDetail scale = createPublishedPlan("scale-usd", SupportedCurrency.USD, "300.00", null, null);

        Instant periodStart = Instant.parse("2026-04-01T00:00:00Z");
        SubscriptionSummary sub = subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                customer.id(), growth.id(), SupportedCurrency.USD, BillingInterval.MONTHLY,
                "UTC", 1, periodStart, null, null, "sched-init"
        ));

        // Schedule downgrade to basic at period end (2026-05-01T00:00:00Z)
        ScheduledPlanChangeRecord firstScheduled = subscriptionManagement.schedulePlanChange(
                sub.id(),
                new SchedulePlanChangeCommand(basic.id(), 1, false, "sched-1")
        );
        assertThat(firstScheduled.status()).isEqualTo(ScheduledChangeStatus.PENDING);
        assertThat(firstScheduled.changeDirection()).isEqualTo(PlanChangeDirection.DOWNGRADE);
        assertThat(firstScheduled.effectiveAt()).isEqualTo(Instant.parse("2026-05-01T00:00:00Z"));

        // Conflicting second pending change without replaceExisting rejected (409)
        assertThatThrownBy(() -> subscriptionManagement.schedulePlanChange(
                sub.id(),
                new SchedulePlanChangeCommand(scale.id(), 1, false, "sched-2")
        )).isInstanceOf(ConflictingSubscriptionException.class);

        // Pausing while pending scheduled change exists is rejected cleanly
        assertThatThrownBy(() -> subscriptionManagement.pauseSubscription(sub.id(), "pause-blocked"))
                .isInstanceOf(ConflictingSubscriptionException.class);

        // Authorized replacement supersedes prior PENDING change atomically
        ScheduledPlanChangeRecord replaced = subscriptionManagement.schedulePlanChange(
                sub.id(),
                new SchedulePlanChangeCommand(scale.id(), 2, true, "sched-replace")
        );
        assertThat(replaced.status()).isEqualTo(ScheduledChangeStatus.PENDING);
        assertThat(replaced.changeDirection()).isEqualTo(PlanChangeDirection.UPGRADE);

        // Execute due scheduled change at period boundary via repeatable maintenance facade
        Instant may1 = Instant.parse("2026-05-01T00:00:00Z");
        MaintenanceProcessingSummary exec = subscriptionMaintenance.processDueOperationsAt(may1);
        assertThat(exec.scheduledChangesApplied()).isEqualTo(1);

        SubscriptionSummary afterApply = subscriptionQuery.findSubscriptionById(sub.id()).orElseThrow();
        assertThat(afterApply.currentPlanVersionId()).isEqualTo(scale.id());
        assertThat(afterApply.seatQuantity()).isEqualTo(2);
        assertThat(afterApply.currentPeriodStart()).isEqualTo(may1);
        assertThat(afterApply.currentPeriodEnd()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));

        // Re-running maintenance is strictly idempotent
        assertThat(subscriptionMaintenance.processDueOperationsAt(may1).scheduledChangesApplied()).isZero();

        // Pause & resume period-shift behavior on a current active subscription
        CustomerSummary custPause = identityManagement.createCustomer("pause-cust", "Pause Cust", SupportedCurrency.USD);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        SubscriptionSummary pauseSub = subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                custPause.id(), basic.id(), SupportedCurrency.USD, BillingInterval.MONTHLY,
                "UTC", 1, now.minus(Duration.ofDays(10)), null, null, "pause-init"
        ));
        SubscriptionSummary paused = subscriptionManagement.pauseSubscription(pauseSub.id(), "p-1");
        assertThat(paused.status()).isEqualTo(SubscriptionStatus.PAUSED);
        assertThat(paused.pausedRemainingSeconds()).isPositive();

        SubscriptionSummary resumed = subscriptionManagement.resumeSubscription(pauseSub.id(), "r-1");
        assertThat(resumed.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(resumed.pausedAt()).isNull();
        assertThat(Duration.between(resumed.currentPeriodStart(), resumed.currentPeriodEnd()).getSeconds())
                .isEqualTo(paused.pausedRemainingSeconds());

        // End-of-period cancellation finalized by maintenance at currentPeriodEnd
        SubscriptionSummary eopScheduled = subscriptionManagement.cancelAtPeriodEnd(resumed.id(), "eop-1");
        assertThat(eopScheduled.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(eopScheduled.cancelAtPeriodEnd()).isTrue();

        MaintenanceProcessingSummary eopRun = subscriptionMaintenance.processDueOperationsAt(eopScheduled.currentPeriodEnd());
        assertThat(eopRun.periodEndCancellationsApplied()).isEqualTo(1);
        assertThat(subscriptionQuery.findSubscriptionById(resumed.id()).orElseThrow().status())
                .isEqualTo(SubscriptionStatus.CANCELLED);
    }

    @Test
    void enforcesRestAuthorizationCustomerIsolationAndConcurrentCreationSafety() throws Exception {
        CustomerSummary tenantA = identityManagement.createCustomer("rest-a", "Tenant A", SupportedCurrency.BDT);
        CustomerSummary tenantB = identityManagement.createCustomer("rest-b", "Tenant B", SupportedCurrency.BDT);
        UserSummary operator = identityManagement.createUser(
                "sub-ops@meterflow.example", "Sub Operator", "SubOpsPass#2026", PlatformRole.OPERATOR);
        UserSummary custUserA = identityManagement.createUser(
                "sub-a@meterflow.example", "Sub Cust A", "SubCustAPass#2026", PlatformRole.CUSTOMER);
        identityManagement.assignMembership(custUserA.id(), tenantA.id(), MembershipRole.OWNER);

        PlanVersionDetail starter = createPublishedPlan("rest-starter", SupportedCurrency.BDT, "1000.00", null, null);
        PlanVersionDetail pro = createPublishedPlan("rest-pro", SupportedCurrency.BDT, "2000.00", null, null);

        // Customer A cannot call operator endpoint POST /api/v1/subscriptions -> 403
        String createBodyA = """
                {
                  "customerId": "%s",
                  "planVersionId": "%s",
                  "currency": "BDT",
                  "billingInterval": "MONTHLY",
                  "billingTimezone": "Asia/Dhaka",
                  "seatQuantity": 2
                }
                """.formatted(tenantA.id(), starter.id());

        mockMvc.perform(post("/api/v1/subscriptions")
                        .with(httpBasic(custUserA.email(), "SubCustAPass#2026"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBodyA))
                .andExpect(status().isForbidden());

        // Operator creates subscriptions for Tenant A and Tenant B via REST
        String responseA = mockMvc.perform(post("/api/v1/subscriptions")
                        .with(httpBasic(operator.email(), "SubOpsPass#2026"))
                        .header("Idempotency-Key", "rest-create-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBodyA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();

        SubscriptionSummary subB = subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                tenantB.id(), starter.id(), SupportedCurrency.BDT, BillingInterval.MONTHLY,
                "Asia/Dhaka", 1, null, null, null, "rest-create-b"
        ));

        UUID subAId = subscriptionQuery.listCustomerSubscriptions(tenantA.id(), org.springframework.data.domain.Pageable.ofSize(10))
                .getContent().getFirst().id();
        assertThat(responseA).contains(subAId.toString());

        // Customer A reads own subscription & history -> 200
        mockMvc.perform(get("/api/v1/customer/" + tenantA.id() + "/subscriptions/" + subAId)
                        .with(httpBasic(custUserA.email(), "SubCustAPass#2026")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(subAId.toString()));

        mockMvc.perform(get("/api/v1/customer/" + tenantA.id() + "/subscriptions/" + subAId + "/history")
                        .with(httpBasic(custUserA.email(), "SubCustAPass#2026")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planAssignments.length()").value(1));

        // Customer A schedules & cancels plan change on OWN subscription -> 201 / 200
        mockMvc.perform(post("/api/v1/customer/" + tenantA.id() + "/subscriptions/" + subAId + "/plan-changes/scheduled")
                        .with(httpBasic(custUserA.email(), "SubCustAPass#2026"))
                        .header("Idempotency-Key", "cust-a-sched-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetPlanVersionId\":\"" + pro.id() + "\",\"targetSeatQuantity\":3}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(delete("/api/v1/customer/" + tenantA.id() + "/subscriptions/" + subAId + "/plan-changes/scheduled")
                        .with(httpBasic(custUserA.email(), "SubCustAPass#2026")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // Customer A attempting cross-tenant access to Tenant B's subscription (both via Tenant B path and Tenant A path) -> 403
        mockMvc.perform(get("/api/v1/customer/" + tenantB.id() + "/subscriptions/" + subB.id())
                        .with(httpBasic(custUserA.email(), "SubCustAPass#2026")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/customer/" + tenantA.id() + "/subscriptions/" + subB.id())
                        .with(httpBasic(custUserA.email(), "SubCustAPass#2026")))
                .andExpect(status().isForbidden());

        // RFC 9457 ProblemDetail on 404
        mockMvc.perform(get("/api/v1/subscriptions/" + UUID.randomUUID())
                        .with(httpBasic(operator.email(), "SubOpsPass#2026")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:meterflow:error:subscription-not-found"));

        // Concurrency test 1: 4 concurrent creates with DISTINCT keys for same customer -> exactly 1 succeeds, 3 conflict
        CustomerSummary raceCustomer1 = identityManagement.createCustomer("race-1", "Race Distinct", SupportedCurrency.BDT);
        CustomerSummary raceCustomer2 = identityManagement.createCustomer("race-2", "Race Idempotent", SupportedCurrency.BDT);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<String>> distinctTasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                final int idx = i;
                distinctTasks.add(() -> {
                    try {
                        subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                                raceCustomer1.id(), starter.id(), SupportedCurrency.BDT, BillingInterval.MONTHLY,
                                "UTC", 1, null, null, null, "distinct-race-" + idx
                        ));
                        return "CREATED";
                    } catch (ConflictingSubscriptionException ex) {
                        return "CONFLICT";
                    }
                });
            }
            List<String> distinctOutcomes = new ArrayList<>();
            for (Future<String> future : pool.invokeAll(distinctTasks)) {
                distinctOutcomes.add(future.get());
            }
            assertThat(distinctOutcomes.stream().filter("CREATED"::equals).count()).isEqualTo(1);
            assertThat(distinctOutcomes.stream().filter("CONFLICT"::equals).count()).isEqualTo(3);

            // Concurrency test 2: 4 concurrent creates with IDENTICAL idempotency key -> all 4 return same subscription ID
            List<Callable<UUID>> idempotentTasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                idempotentTasks.add(() -> subscriptionManagement.createSubscription(new CreateSubscriptionCommand(
                        raceCustomer2.id(), starter.id(), SupportedCurrency.BDT, BillingInterval.MONTHLY,
                        "UTC", 1, null, null, null, "same-race-key"
                )).id());
            }
            Set<UUID> returnedIds = pool.invokeAll(idempotentTasks).stream()
                    .map(f -> {
                        try {
                            return f.get();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .collect(Collectors.toSet());
            assertThat(returnedIds).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private PlanVersionDetail createDraftPlan(
            String planCode,
            SupportedCurrency currency,
            String flatAmount,
            String perSeatRate,
            UUID usageMetricId
    ) {
        PlanVersionDetail draft = planCatalog.createPlanWithDraft(new CreatePlanCommand(
                planCode, planCode.toUpperCase(), "Test plan", currency, Instant.parse("2026-01-01T00:00:00Z")
        ));
        List<ConfigureChargeCommand> charges = new ArrayList<>();
        int order = 1;
        if (flatAmount != null) {
            charges.add(new ConfigureChargeCommand(
                    order++, "base_flat", "Base Fee", ChargeTypes.FLAT_FEE, null,
                    new BigDecimal(flatAmount), null, null, null, List.of(), Map.of()
            ));
        }
        if (perSeatRate != null) {
            charges.add(new ConfigureChargeCommand(
                    order++, "seat_fee", "Seat Fee", ChargeTypes.PER_SEAT, null,
                    null, new BigDecimal(perSeatRate), null, null, List.of(), Map.of()
            ));
        }
        if (usageMetricId != null) {
            charges.add(new ConfigureChargeCommand(
                    order, "usage_calls", "API Usage", ChargeTypes.PER_UNIT, usageMetricId,
                    null, new BigDecimal("2.00"), null, null, List.of(), Map.of()
            ));
        }
        return planCatalog.updateDraftVersion(draft.id(), new UpdateDraftPlanVersionCommand(
                Instant.parse("2026-01-01T00:00:00Z"), charges, List.of(), List.of()
        ));
    }

    private PlanVersionDetail createPublishedPlan(
            String planCode,
            SupportedCurrency currency,
            String flatAmount,
            String perSeatRate,
            UUID usageMetricId
    ) {
        PlanVersionDetail draft = createDraftPlan(planCode, currency, flatAmount, perSeatRate, usageMetricId);
        return planCatalog.publishVersion(draft.id());
    }
}
