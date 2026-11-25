package com.meterflow.portal.internal;

import com.meterflow.billing.api.ConfigureTaxPolicyCommand;
import com.meterflow.billing.api.CreditGrantType;
import com.meterflow.billing.api.FinalizeInvoiceCommand;
import com.meterflow.billing.api.GenerateDraftInvoiceCommand;
import com.meterflow.billing.api.InvoiceDetail;
import com.meterflow.billing.api.InvoiceManagementFacade;
import com.meterflow.billing.api.IssueCreditGrantCommand;
import com.meterflow.billing.api.PrepaidCreditFacade;
import com.meterflow.billing.api.TaxPolicyFacade;
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
import com.meterflow.identity.api.DemoAccountCatalog;
import com.meterflow.identity.api.IdentityQueryFacade;
import com.meterflow.metering.api.IngestUsageBatchCommand;
import com.meterflow.metering.api.IngestUsageEventCommand;
import com.meterflow.metering.api.UsageIngestionFacade;
import com.meterflow.shared.BillingInterval;
import com.meterflow.shared.SupportedCurrency;
import com.meterflow.subscriptions.api.CreateSubscriptionCommand;
import com.meterflow.subscriptions.api.SubscriptionManagementFacade;
import com.meterflow.subscriptions.api.SubscriptionQueryFacade;
import com.meterflow.subscriptions.api.SubscriptionSummary;
import com.meterflow.subscriptions.api.TrialConversionPolicy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Idempotent synthetic demo workspace initializer scoped strictly to the isolated demo customer tenant.
 *
 * <p>Runs only when both {@code meterflow.demo.enabled=true} and {@code meterflow.demo.seed-sample-data=true}.
 * Uses only public module facades so every record satisfies domain validation and PostgreSQL invariants.
 */
@Component
@Order(20)
@ConditionalOnProperty(name = {"meterflow.demo.enabled", "meterflow.demo.seed-sample-data"}, havingValue = "true")
class DemoSampleDataRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSampleDataRunner.class);
    private static final String DEMO_METRIC_KEY = "demo_api_calls";
    private static final String DEMO_STARTER_PLAN_CODE = "DEMO-STARTER";
    private static final String DEMO_GROWTH_PLAN_CODE = "DEMO-GROWTH";

    private final DemoAccountCatalog demoAccountCatalog;
    private final IdentityQueryFacade identityQueryFacade;
    private final MetricCatalogFacade metricCatalogFacade;
    private final PlanCatalogFacade planCatalogFacade;
    private final SubscriptionQueryFacade subscriptionQueryFacade;
    private final SubscriptionManagementFacade subscriptionManagementFacade;
    private final UsageIngestionFacade usageIngestionFacade;
    private final PrepaidCreditFacade prepaidCreditFacade;
    private final TaxPolicyFacade taxPolicyFacade;
    private final InvoiceManagementFacade invoiceManagementFacade;
    private final Clock clock;

    DemoSampleDataRunner(
            DemoAccountCatalog demoAccountCatalog,
            IdentityQueryFacade identityQueryFacade,
            MetricCatalogFacade metricCatalogFacade,
            PlanCatalogFacade planCatalogFacade,
            SubscriptionQueryFacade subscriptionQueryFacade,
            SubscriptionManagementFacade subscriptionManagementFacade,
            UsageIngestionFacade usageIngestionFacade,
            PrepaidCreditFacade prepaidCreditFacade,
            TaxPolicyFacade taxPolicyFacade,
            InvoiceManagementFacade invoiceManagementFacade,
            Clock clock
    ) {
        this.demoAccountCatalog = demoAccountCatalog;
        this.identityQueryFacade = identityQueryFacade;
        this.metricCatalogFacade = metricCatalogFacade;
        this.planCatalogFacade = planCatalogFacade;
        this.subscriptionQueryFacade = subscriptionQueryFacade;
        this.subscriptionManagementFacade = subscriptionManagementFacade;
        this.usageIngestionFacade = usageIngestionFacade;
        this.prepaidCreditFacade = prepaidCreditFacade;
        this.taxPolicyFacade = taxPolicyFacade;
        this.invoiceManagementFacade = invoiceManagementFacade;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedSyntheticDemoWorkspace();
    }

    void seedSyntheticDemoWorkspace() {
        String demoCustomerKey = demoAccountCatalog.getDemoCustomerExternalKey();
        if (demoCustomerKey == null || demoCustomerKey.isBlank()) {
            return;
        }
        Optional<CustomerSummary> customerOpt = identityQueryFacade.findCustomerByExternalKey(demoCustomerKey);
        if (customerOpt.isEmpty()) {
            return;
        }
        CustomerSummary demoCustomer = customerOpt.get();

        // Fast idempotency check: if demo customer already has an open subscription, preserve existing demo state.
        if (subscriptionQueryFacade.findOpenSubscriptionByCustomerId(demoCustomer.id()).isPresent()) {
            log.info("Synthetic demo workspace already present for tenant={}; skipping sample seeding", demoCustomerKey);
            return;
        }

        try {
            Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
            Instant effectiveFrom = now.minus(60, ChronoUnit.DAYS);

            BillableMetricDefinition metric = metricCatalogFacade
                    .findMetricByKey(DEMO_METRIC_KEY, demoCustomer.id())
                    .orElseGet(() -> metricCatalogFacade.createMetric(new CreateMetricCommand(
                            DEMO_METRIC_KEY,
                            "Demo API Calls",
                            "Synthetic API call counter for academic demonstration",
                            MetricAggregationType.SUM,
                            "quantity",
                            demoCustomer.id(),
                            List.of()
                    )));

            PlanVersionDetail starterPlan = ensurePublishedDemoPlan(
                    DEMO_STARTER_PLAN_CODE,
                    "Demo Starter Plan",
                    "Base monthly fee + standard per-unit API call rate (synthetic demo plan)",
                    effectiveFrom,
                    metric,
                    new BigDecimal("1500.00"),
                    new BigDecimal("0.50")
            );
            ensurePublishedDemoPlan(
                    DEMO_GROWTH_PLAN_CODE,
                    "Demo Growth Plan",
                    "Higher base fee with 50% lower per-unit API rate for FR10 plan comparison",
                    effectiveFrom,
                    metric,
                    new BigDecimal("2500.00"),
                    new BigDecimal("0.25")
            );

            ZonedDateTime monthStart = now.atZone(ZoneOffset.UTC)
                    .withDayOfMonth(1)
                    .truncatedTo(ChronoUnit.DAYS);
            Instant subStart = monthStart.toInstant();

            SubscriptionSummary subscription = subscriptionManagementFacade.createSubscription(new CreateSubscriptionCommand(
                    demoCustomer.id(),
                    starterPlan.id(),
                    SupportedCurrency.BDT,
                    BillingInterval.MONTHLY,
                    "UTC",
                    5,
                    subStart,
                    null,
                    TrialConversionPolicy.EXPIRE_ON_END,
                    "demo-sub-" + demoCustomerKey + "-001"
            ));

            try {
                taxPolicyFacade.configureTaxPolicy(new ConfigureTaxPolicyCommand(
                        "DEMO-BD-VAT",
                        "Demo Standard VAT (15%)",
                        demoCustomer.id(),
                        "BD",
                        "VAT",
                        new BigDecimal("15.0000"),
                        false
                ));
            } catch (RuntimeException ex) {
                log.debug("Demo tax policy already configured: {}", ex.getMessage());
            }

            prepaidCreditFacade.issueCreditGrant(new IssueCreditGrantCommand(
                    demoCustomer.id(),
                    SupportedCurrency.BDT,
                    CreditGrantType.PROMOTIONAL,
                    "Synthetic Demo Welcome Credit",
                    new BigDecimal("500.00"),
                    10,
                    now.plus(180, ChronoUnit.DAYS),
                    "demo-credit-" + demoCustomerKey + "-001"
            ));

            List<IngestUsageEventCommand> sampleEvents = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                Instant eventTime = now.minus(i, ChronoUnit.HOURS);
                if (eventTime.isBefore(subscription.currentPeriodStart())) {
                    eventTime = subscription.currentPeriodStart().plus(i, ChronoUnit.MINUTES);
                }
                sampleEvents.add(new IngestUsageEventCommand(
                        null,
                        demoCustomer.id(),
                        metric.id(),
                        subscription.id(),
                        new BigDecimal("1000"),
                        eventTime,
                        "demo-evt-" + demoCustomerKey + "-00" + i,
                        Map.of("quantity", 1000, "endpoint", "/v1/demo/predict", "region", "ap-south")
                ));
            }
            usageIngestionFacade.ingestBatch(new IngestUsageBatchCommand(sampleEvents));

            InvoiceDetail draft = invoiceManagementFacade.generateOrRefreshDraft(new GenerateDraftInvoiceCommand(
                    subscription.id(),
                    subscription.currentPeriodStart(),
                    subscription.currentPeriodEnd(),
                    0,
                    null,
                    "demo-draft-" + demoCustomerKey + "-001"
            ));
            invoiceManagementFacade.finalizeInvoice(
                    draft.invoice().id(),
                    new FinalizeInvoiceCommand(true, now.plus(14, ChronoUnit.DAYS), "demo-finalize-" + demoCustomerKey + "-001")
            );

            log.info("Synthetic demo billing workspace seeded cleanly for tenant={}", demoCustomerKey);
        } catch (RuntimeException ex) {
            log.warn("Skipped optional synthetic demo sample data seeding: {}", ex.getMessage());
        }
    }

    private PlanVersionDetail ensurePublishedDemoPlan(
            String planCode,
            String planName,
            String description,
            Instant effectiveFrom,
            BillableMetricDefinition metric,
            BigDecimal flatFee,
            BigDecimal unitPrice
    ) {
        return planCatalogFacade.listPlans(org.springframework.data.domain.PageRequest.of(0, 100))
                .getContent().stream()
                .filter(plan -> planCode.equalsIgnoreCase(plan.planCode()))
                .findFirst()
                .flatMap(plan -> planCatalogFacade.listPlanVersions(plan.id()).stream()
                        .filter(v -> v.status() == com.meterflow.catalog.api.PlanVersionStatus.PUBLISHED)
                        .findFirst())
                .orElseGet(() -> {
                    PlanVersionDetail draft = planCatalogFacade.createPlanWithDraft(new CreatePlanCommand(
                            planCode,
                            planName,
                            description,
                            SupportedCurrency.BDT,
                            effectiveFrom
                    ));
                    planCatalogFacade.updateDraftVersion(draft.id(), new UpdateDraftPlanVersionCommand(
                            effectiveFrom,
                            List.of(
                                    new ConfigureChargeCommand(
                                            1, "BASE_FEE", "Monthly Platform Fee", ChargeTypes.FLAT_FEE,
                                            null, flatFee, null, null, null, List.of(), Map.of()
                                    ),
                                    new ConfigureChargeCommand(
                                            2, "API_USAGE", "Metered API Calls", ChargeTypes.PER_UNIT,
                                            metric.id(), null, unitPrice, null, null, List.of(), Map.of()
                                    )
                            ),
                            List.of(),
                            List.of()
                    ));
                    return planCatalogFacade.publishVersion(draft.id());
                });
    }
}
