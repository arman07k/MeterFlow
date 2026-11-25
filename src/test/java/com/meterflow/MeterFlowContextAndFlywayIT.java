package com.meterflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.meterflow.billing.api.BillingAdjustmentFacade;
import com.meterflow.billing.api.BillingRunFacade;
import com.meterflow.billing.api.CreditNoteFacade;
import com.meterflow.billing.api.HistoricalInvoiceSimulationFacade;
import com.meterflow.billing.api.InvoiceManagementFacade;
import com.meterflow.billing.api.PrepaidCreditFacade;
import com.meterflow.billing.api.TaxPolicyFacade;
import com.meterflow.catalog.api.MetricCatalogFacade;
import com.meterflow.catalog.api.PlanCatalogFacade;
import com.meterflow.catalog.api.PricingSimulationFacade;
import com.meterflow.catalog.api.RatingEngineFacade;
import com.meterflow.identity.api.CustomerAccessGuard;
import com.meterflow.identity.api.IdentityManagementFacade;
import com.meterflow.identity.api.IdentityQueryFacade;
import com.meterflow.metering.api.LateUsageQueryFacade;
import com.meterflow.metering.api.UsageIngestionFacade;
import com.meterflow.metering.api.UsageQueryFacade;
import com.meterflow.metering.api.UsageReconciliationFacade;
import com.meterflow.metering.api.UsageThresholdFacade;
import com.meterflow.notifications.api.AuditLogFacade;
import com.meterflow.notifications.api.NotificationFacade;
import com.meterflow.payments.api.DunningManagementFacade;
import com.meterflow.payments.api.DunningPolicyFacade;
import com.meterflow.payments.api.PaymentProcessingFacade;
import com.meterflow.payments.api.PaymentQueryFacade;
import com.meterflow.shared.CacheNames;
import com.meterflow.shared.OutboxRetryScheduler;
import com.meterflow.shared.SchedulingConfiguration;
import com.meterflow.subscriptions.api.SubscriptionMaintenanceFacade;
import com.meterflow.subscriptions.api.SubscriptionManagementFacade;
import com.meterflow.subscriptions.api.SubscriptionQueryFacade;
import com.meterflow.webhooks.api.WebhookDeliveryFacade;
import com.meterflow.webhooks.api.WebhookEndpointFacade;
import io.swagger.v3.oas.models.OpenAPI;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(MeterFlowContextAndFlywayIT.PostgresContainerConfiguration.class)
class MeterFlowContextAndFlywayIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresContainerConfiguration {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer("postgres:17-alpine");
        }
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobOperator jobOperator;

    @Autowired
    private OutboxRetryScheduler outboxRetryScheduler;

    @Autowired
    private OpenAPI openApi;

    @Autowired
    private UserDetailsService userDetailsService;

    @Autowired
    private IdentityManagementFacade identityManagementFacade;

    @Autowired
    private IdentityQueryFacade identityQueryFacade;

    @Autowired
    private CustomerAccessGuard customerAccessGuard;

    @Autowired
    private MetricCatalogFacade metricCatalogFacade;

    @Autowired
    private PlanCatalogFacade planCatalogFacade;

    @Autowired
    private RatingEngineFacade ratingEngineFacade;

    @Autowired
    private PricingSimulationFacade pricingSimulationFacade;

    @Autowired
    private SubscriptionManagementFacade subscriptionManagementFacade;

    @Autowired
    private SubscriptionQueryFacade subscriptionQueryFacade;

    @Autowired
    private SubscriptionMaintenanceFacade subscriptionMaintenanceFacade;

    @Autowired
    private UsageIngestionFacade usageIngestionFacade;

    @Autowired
    private UsageQueryFacade usageQueryFacade;

    @Autowired
    private LateUsageQueryFacade lateUsageQueryFacade;

    @Autowired
    private UsageReconciliationFacade usageReconciliationFacade;

    @Autowired
    private UsageThresholdFacade usageThresholdFacade;

    @Autowired
    private InvoiceManagementFacade invoiceManagementFacade;

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
    private PaymentProcessingFacade paymentProcessingFacade;

    @Autowired
    private PaymentQueryFacade paymentQueryFacade;

    @Autowired
    private DunningManagementFacade dunningManagementFacade;

    @Autowired
    private DunningPolicyFacade dunningPolicyFacade;

    @Autowired
    private WebhookEndpointFacade webhookEndpointFacade;

    @Autowired
    private WebhookDeliveryFacade webhookDeliveryFacade;

    @Autowired
    private NotificationFacade notificationFacade;

    @Autowired
    private AuditLogFacade auditLogFacade;

    @Autowired
    private HistoricalInvoiceSimulationFacade historicalInvoiceSimulationFacade;

    @Test
    void flywayAppliesV1ThroughV8SchemasAndValidatesCleanly() {
        assertThat(flyway.info().applied())
                .extracting(info -> info.getVersion().getVersion())
                .contains("1", "2", "3", "4", "5", "6", "7", "8");

        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class
        );

        assertThat(tables).contains(
                "event_publication",
                "batch_job_instance",
                "batch_job_execution",
                "batch_job_execution_params",
                "batch_step_execution",
                "batch_step_execution_context",
                "batch_job_execution_context",
                "customers",
                "users",
                "customer_memberships",
                "billable_metrics",
                "metric_filters",
                "pricing_plans",
                "plan_versions",
                "plan_charges",
                "charge_tiers",
                "free_allowances",
                "minimum_commitments",
                "subscriptions",
                "subscription_plan_assignments",
                "subscription_scheduled_changes",
                "subscription_proration_records",
                "subscription_state_transitions",
                "usage_events",
                "usage_rollups",
                "usage_late_event_records",
                "usage_rejected_events",
                "usage_threshold_evaluations",
                "billing_runs",
                "tax_policies",
                "invoices",
                "invoice_lines",
                "invoice_tax_lines",
                "prepaid_credit_grants",
                "credit_transactions",
                "credit_notes",
                "credit_note_lines",
                "billing_adjustments",
                "dunning_policies",
                "dunning_cases",
                "payment_transactions",
                "payment_attempts",
                "dunning_events",
                "webhook_endpoints",
                "webhook_deliveries",
                "webhook_delivery_attempts",
                "notifications",
                "audit_logs"
        );

        List<String> outboxColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'event_publication'",
                String.class
        );
        assertThat(outboxColumns).contains(
                "id",
                "listener_id",
                "event_type",
                "serialized_event",
                "publication_date",
                "completion_date",
                "status",
                "completion_attempts",
                "last_resubmission_date"
        );
    }

    @Test
    void foundationInfrastructureBeansAreWiredAndSchedulingIsDisabledInTestProfile() {
        assertThat(cacheManager).isInstanceOf(CaffeineCacheManager.class);
        assertThat(cacheManager.getCacheNames()).containsExactlyInAnyOrder(
                CacheNames.PLAN_VERSIONS,
                CacheNames.METRIC_DEFINITIONS,
                CacheNames.ACTIVE_SUBSCRIPTION_LOOKUP,
                CacheNames.LIVE_ESTIMATES,
                CacheNames.DASHBOARD_SUMMARIES
        );
        assertThat(jobRepository).isNotNull();
        assertThat(jobOperator).isNotNull();
        assertThat(outboxRetryScheduler).isNotNull();
        assertThatCode(outboxRetryScheduler::resubmitIncompleteEvents).doesNotThrowAnyException();
        assertThat(applicationContext.getBeansOfType(SchedulingConfiguration.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(ScheduledTaskHolder.class)).isEmpty();
        assertThat(userDetailsService).isNotNull();
        assertThat(identityManagementFacade).isNotNull();
        assertThat(identityQueryFacade).isNotNull();
        assertThat(customerAccessGuard).isNotNull();
        assertThat(metricCatalogFacade).isNotNull();
        assertThat(planCatalogFacade).isNotNull();
        assertThat(ratingEngineFacade).isNotNull();
        assertThat(pricingSimulationFacade).isNotNull();
        assertThat(subscriptionManagementFacade).isNotNull();
        assertThat(subscriptionQueryFacade).isNotNull();
        assertThat(subscriptionMaintenanceFacade).isNotNull();
        assertThat(usageIngestionFacade).isNotNull();
        assertThat(usageQueryFacade).isNotNull();
        assertThat(lateUsageQueryFacade).isNotNull();
        assertThat(usageReconciliationFacade).isNotNull();
        assertThat(usageThresholdFacade).isNotNull();
        assertThat(invoiceManagementFacade).isNotNull();
        assertThat(billingRunFacade).isNotNull();
        assertThat(taxPolicyFacade).isNotNull();
        assertThat(prepaidCreditFacade).isNotNull();
        assertThat(creditNoteFacade).isNotNull();
        assertThat(billingAdjustmentFacade).isNotNull();
        assertThat(paymentProcessingFacade).isNotNull();
        assertThat(paymentQueryFacade).isNotNull();
        assertThat(dunningManagementFacade).isNotNull();
        assertThat(dunningPolicyFacade).isNotNull();
        assertThat(webhookEndpointFacade).isNotNull();
        assertThat(webhookDeliveryFacade).isNotNull();
        assertThat(notificationFacade).isNotNull();
        assertThat(auditLogFacade).isNotNull();
        assertThat(historicalInvoiceSimulationFacade).isNotNull();
        assertThat(openApi.getInfo().getTitle()).isEqualTo("MeterFlow API");
    }
}
