package com.meterflow.portal.internal;

import com.meterflow.billing.api.BillingAdjustmentFacade;
import com.meterflow.billing.api.BillingRunCommand;
import com.meterflow.billing.api.BillingRunFacade;
import com.meterflow.billing.api.ConfigureTaxPolicyCommand;
import com.meterflow.billing.api.FinalizeInvoiceCommand;
import com.meterflow.billing.api.HistoricalInvoiceComparisonResult;
import com.meterflow.billing.api.HistoricalInvoiceSimulationFacade;
import com.meterflow.billing.api.HistoricalInvoiceSimulationRequest;
import com.meterflow.billing.api.InvoiceManagementFacade;
import com.meterflow.billing.api.InvoiceStatus;
import com.meterflow.billing.api.IssueCreditGrantCommand;
import com.meterflow.billing.api.PrepaidCreditFacade;
import com.meterflow.billing.api.CreditGrantType;
import com.meterflow.billing.api.TaxPolicyFacade;
import com.meterflow.catalog.api.MetricCatalogFacade;
import com.meterflow.catalog.api.PlanCatalogFacade;
import com.meterflow.catalog.api.PlanVersionDetail;
import com.meterflow.catalog.api.PricingPlanSummary;
import com.meterflow.identity.api.CustomerSummary;
import com.meterflow.identity.api.IdentityManagementFacade;
import com.meterflow.identity.api.IdentityQueryFacade;
import com.meterflow.notifications.api.AuditLogFacade;
import com.meterflow.payments.api.DunningCaseStatus;
import com.meterflow.payments.api.DunningExecutionSummary;
import com.meterflow.payments.api.DunningManagementFacade;
import com.meterflow.payments.api.InitiatePaymentCommand;
import com.meterflow.payments.api.MockPaymentOutcome;
import com.meterflow.payments.api.PaymentProcessingFacade;
import com.meterflow.payments.api.PaymentQueryFacade;
import com.meterflow.payments.api.PaymentStatus;
import com.meterflow.shared.SupportedCurrency;
import com.meterflow.subscriptions.api.SubscriptionManagementFacade;
import com.meterflow.subscriptions.api.SubscriptionQueryFacade;
import com.meterflow.subscriptions.api.SubscriptionStatus;
import com.meterflow.webhooks.api.WebhookDeliveryFacade;
import com.meterflow.webhooks.api.WebhookDeliveryStatus;
import com.meterflow.webhooks.api.WebhookDispatchSummary;
import com.meterflow.webhooks.api.WebhookEndpointFacade;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Server-rendered Thymeleaf Admin and Operator Console (/operator/** and /admin/**).
 */
@Controller
class OperatorPortalController {

    private final IdentityQueryFacade identityQueryFacade;
    private final IdentityManagementFacade identityManagementFacade;
    private final SubscriptionQueryFacade subscriptionQueryFacade;
    private final SubscriptionManagementFacade subscriptionManagementFacade;
    private final InvoiceManagementFacade invoiceManagementFacade;
    private final BillingRunFacade billingRunFacade;
    private final TaxPolicyFacade taxPolicyFacade;
    private final PrepaidCreditFacade prepaidCreditFacade;
    private final BillingAdjustmentFacade billingAdjustmentFacade;
    private final HistoricalInvoiceSimulationFacade historicalSimulationFacade;
    private final PaymentQueryFacade paymentQueryFacade;
    private final PaymentProcessingFacade paymentProcessingFacade;
    private final DunningManagementFacade dunningManagementFacade;
    private final WebhookEndpointFacade webhookEndpointFacade;
    private final WebhookDeliveryFacade webhookDeliveryFacade;
    private final AuditLogFacade auditLogFacade;
    private final PlanCatalogFacade planCatalogFacade;
    private final MetricCatalogFacade metricCatalogFacade;

    OperatorPortalController(
            IdentityQueryFacade identityQueryFacade,
            IdentityManagementFacade identityManagementFacade,
            SubscriptionQueryFacade subscriptionQueryFacade,
            SubscriptionManagementFacade subscriptionManagementFacade,
            InvoiceManagementFacade invoiceManagementFacade,
            BillingRunFacade billingRunFacade,
            TaxPolicyFacade taxPolicyFacade,
            PrepaidCreditFacade prepaidCreditFacade,
            BillingAdjustmentFacade billingAdjustmentFacade,
            HistoricalInvoiceSimulationFacade historicalSimulationFacade,
            PaymentQueryFacade paymentQueryFacade,
            PaymentProcessingFacade paymentProcessingFacade,
            DunningManagementFacade dunningManagementFacade,
            WebhookEndpointFacade webhookEndpointFacade,
            WebhookDeliveryFacade webhookDeliveryFacade,
            AuditLogFacade auditLogFacade,
            PlanCatalogFacade planCatalogFacade,
            MetricCatalogFacade metricCatalogFacade
    ) {
        this.identityQueryFacade = identityQueryFacade;
        this.identityManagementFacade = identityManagementFacade;
        this.subscriptionQueryFacade = subscriptionQueryFacade;
        this.subscriptionManagementFacade = subscriptionManagementFacade;
        this.invoiceManagementFacade = invoiceManagementFacade;
        this.billingRunFacade = billingRunFacade;
        this.taxPolicyFacade = taxPolicyFacade;
        this.prepaidCreditFacade = prepaidCreditFacade;
        this.billingAdjustmentFacade = billingAdjustmentFacade;
        this.historicalSimulationFacade = historicalSimulationFacade;
        this.paymentQueryFacade = paymentQueryFacade;
        this.paymentProcessingFacade = paymentProcessingFacade;
        this.dunningManagementFacade = dunningManagementFacade;
        this.webhookEndpointFacade = webhookEndpointFacade;
        this.webhookDeliveryFacade = webhookDeliveryFacade;
        this.auditLogFacade = auditLogFacade;
        this.planCatalogFacade = planCatalogFacade;
        this.metricCatalogFacade = metricCatalogFacade;
    }

    @GetMapping({ "/admin", "/admin/", "/admin/overview" })
    String adminOverviewRedirect() {
        return "redirect:/operator/overview";
    }

    @GetMapping({ "/operator", "/operator/", "/operator/overview" })
    String overview(Model model) {
        model.addAttribute("activeTab", "overview");
        model.addAttribute("customerCount", identityQueryFacade.countCustomers());
        model.addAttribute("subscriptionCounts", subscriptionQueryFacade.countSubscriptionsByStatus());
        model.addAttribute("webhookCounts", webhookDeliveryFacade.countDeliveriesByStatus());
        model.addAttribute("recentRuns", billingRunFacade.listBillingRuns(PageRequest.of(0, 5)).getContent());
        model.addAttribute("recentInvoices", invoiceManagementFacade.listInvoices(null, PageRequest.of(0, 6)).getContent());
        model.addAttribute("activeDunning", paymentQueryFacade.listDunningCases(DunningCaseStatus.RETRY_SCHEDULED, PageRequest.of(0, 5)).getContent());
        return "operator/overview";
    }

    @GetMapping("/operator/customers")
    String customers(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            Model model
    ) {
        model.addAttribute("activeTab", "customers");
        model.addAttribute("search", search != null ? search : "");
        model.addAttribute("currencies", SupportedCurrency.values());
        model.addAttribute("customersPage", identityQueryFacade.listCustomers(search, PageRequest.of(Math.max(0, page), 15)));
        return "operator/customers";
    }

    @PostMapping("/operator/customers")
    String createCustomer(
            @RequestParam String externalKey,
            @RequestParam String name,
            @RequestParam SupportedCurrency currency,
            RedirectAttributes redirectAttributes
    ) {
        try {
            CustomerSummary created = identityManagementFacade.createCustomer(externalKey, name, currency);
            redirectAttributes.addFlashAttribute("successMessage", "Created customer " + created.name() + " (" + created.externalKey() + ").");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/operator/customers";
    }

    @GetMapping("/operator/customers/{customerId}")
    String customerDetail(@PathVariable UUID customerId, Model model) {
        model.addAttribute("activeTab", "customers");
        CustomerSummary customer = identityQueryFacade.findCustomerById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found"));
        model.addAttribute("customer", customer);
        model.addAttribute("subscriptions", subscriptionQueryFacade.listCustomerSubscriptions(customerId, PageRequest.of(0, 10)).getContent());
        model.addAttribute("invoices", invoiceManagementFacade.listCustomerInvoices(customerId, null, PageRequest.of(0, 10)).getContent());
        model.addAttribute("payments", paymentQueryFacade.listCustomerPayments(customerId, PageRequest.of(0, 10)).getContent());
        model.addAttribute("creditBalance", prepaidCreditFacade.getCustomerCreditBalance(customerId, customer.currency()));
        model.addAttribute("adjustments", billingAdjustmentFacade.listCustomerAdjustments(customerId, PageRequest.of(0, 10)).getContent());
        model.addAttribute("endpoints", webhookEndpointFacade.listCustomerEndpoints(customerId));
        return "operator/customer-detail";
    }

    @GetMapping("/operator/subscriptions")
    String subscriptions(
            @RequestParam(required = false) SubscriptionStatus status,
            @RequestParam(defaultValue = "0") int page,
            Model model
    ) {
        model.addAttribute("activeTab", "subscriptions");
        model.addAttribute("selectedStatus", status);
        model.addAttribute("statuses", SubscriptionStatus.values());
        model.addAttribute("subscriptionsPage", subscriptionQueryFacade.listSubscriptions(status, PageRequest.of(Math.max(0, page), 15)));
        return "operator/subscriptions";
    }

    @PostMapping("/operator/subscriptions/{subscriptionId}/pause")
    String operatorPauseSubscription(@PathVariable UUID subscriptionId, RedirectAttributes redirectAttributes) {
        try {
            subscriptionManagementFacade.pauseSubscription(subscriptionId, "op-pause-" + UUID.randomUUID());
            redirectAttributes.addFlashAttribute("successMessage", "Subscription paused.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/operator/subscriptions";
    }

    @PostMapping("/operator/subscriptions/{subscriptionId}/resume")
    String operatorResumeSubscription(@PathVariable UUID subscriptionId, RedirectAttributes redirectAttributes) {
        try {
            subscriptionManagementFacade.resumeSubscription(subscriptionId, "op-resume-" + UUID.randomUUID());
            redirectAttributes.addFlashAttribute("successMessage", "Subscription resumed.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/operator/subscriptions";
    }

    @GetMapping("/operator/billing")
    String billing(
            @RequestParam(required = false) InvoiceStatus status,
            @RequestParam(defaultValue = "0") int page,
            Model model
    ) {
        model.addAttribute("activeTab", "billing");
        model.addAttribute("selectedStatus", status);
        model.addAttribute("statuses", InvoiceStatus.values());
        model.addAttribute("currencies", SupportedCurrency.values());
        model.addAttribute("creditGrantTypes", CreditGrantType.values());
        model.addAttribute("invoicesPage", invoiceManagementFacade.listInvoices(status, PageRequest.of(Math.max(0, page), 15)));
        model.addAttribute("billingRuns", billingRunFacade.listBillingRuns(PageRequest.of(0, 10)).getContent());
        model.addAttribute("taxPolicies", taxPolicyFacade.listTaxPolicies());
        return "operator/billing";
    }

    @PostMapping("/operator/billing/runs/draft")
    String triggerDraftRun(RedirectAttributes redirectAttributes) {
        try {
            var run = billingRunFacade.runDraftGenerationBatch(new BillingRunCommand(null, null, null, null));
            redirectAttributes.addFlashAttribute("successMessage", "Draft batch run " + run.id() + " finished with status " + run.status());
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/operator/billing";
    }

    @PostMapping("/operator/billing/runs/finalize")
    String triggerFinalizeRun(RedirectAttributes redirectAttributes) {
        try {
            var run = billingRunFacade.runFinalizationBatch(new BillingRunCommand(null, null, null, null));
            redirectAttributes.addFlashAttribute("successMessage", "Finalization batch run " + run.id() + " finished with status " + run.status());
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/operator/billing";
    }

    @PostMapping("/operator/billing/runs/{runId}/resume")
    String resumeBillingRun(@PathVariable UUID runId, RedirectAttributes redirectAttributes) {
        try {
            var run = billingRunFacade.resumeFailedBillingRun(runId);
            redirectAttributes.addFlashAttribute("successMessage", "Resumed billing run " + run.id() + " -> " + run.status());
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/operator/billing";
    }

    @PostMapping("/operator/billing/invoices/{invoiceId}/finalize")
    String finalizeInvoice(@PathVariable UUID invoiceId, RedirectAttributes redirectAttributes) {
        try {
            var finalized = invoiceManagementFacade.finalizeInvoice(invoiceId, new FinalizeInvoiceCommand(true, null, null));
            redirectAttributes.addFlashAttribute("successMessage", "Finalized invoice " + finalized.invoice().invoiceNumber());
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/operator/billing";
    }

    @PostMapping("/operator/billing/tax-policies")
    String configureTaxPolicy(
            @RequestParam String policyCode,
            @RequestParam String name,
            @RequestParam String jurisdiction,
            @RequestParam String taxName,
            @RequestParam BigDecimal ratePercent,
            @RequestParam(defaultValue = "false") boolean taxExempt,
            RedirectAttributes redirectAttributes
    ) {
        try {
            taxPolicyFacade.configureTaxPolicy(new ConfigureTaxPolicyCommand(
                    policyCode, name, null, jurisdiction, taxName, ratePercent, taxExempt
            ));
            redirectAttributes.addFlashAttribute("successMessage", "Configured tax policy " + policyCode);
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/operator/billing";
    }

    @PostMapping("/operator/billing/credits/grants")
    String issueCreditGrant(
            @RequestParam UUID customerId,
            @RequestParam SupportedCurrency currency,
            @RequestParam CreditGrantType grantType,
            @RequestParam String description,
            @RequestParam BigDecimal amount,
            RedirectAttributes redirectAttributes
    ) {
        try {
            prepaidCreditFacade.issueCreditGrant(new IssueCreditGrantCommand(
                    customerId, currency, grantType, description, amount, 100, null, "op-grant-" + UUID.randomUUID()
            ));
            redirectAttributes.addFlashAttribute("successMessage", "Issued prepaid credit grant to customer " + customerId);
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/operator/billing";
    }

    @PostMapping("/operator/billing/adjustments/late-usage")
    String processLateUsage(RedirectAttributes redirectAttributes) {
        try {
            var summary = billingAdjustmentFacade.processPendingLateUsage(null);
            redirectAttributes.addFlashAttribute("successMessage", "Processed late usage: created " + summary.adjustmentsCreated() + " adjustments.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/operator/billing";
    }

    @GetMapping("/operator/payments")
    String payments(
            @RequestParam(required = false) PaymentStatus paymentStatus,
            @RequestParam(required = false) DunningCaseStatus dunningStatus,
            @RequestParam(defaultValue = "0") int page,
            Model model
    ) {
        model.addAttribute("activeTab", "payments");
        model.addAttribute("paymentStatuses", PaymentStatus.values());
        model.addAttribute("dunningStatuses", DunningCaseStatus.values());
        model.addAttribute("mockOutcomes", MockPaymentOutcome.values());
        model.addAttribute("currencies", SupportedCurrency.values());
        model.addAttribute("selectedPaymentStatus", paymentStatus);
        model.addAttribute("selectedDunningStatus", dunningStatus);
        model.addAttribute("paymentsPage", paymentQueryFacade.listAllPayments(paymentStatus, PageRequest.of(Math.max(0, page), 15)));
        model.addAttribute("dunningPage", paymentQueryFacade.listDunningCases(dunningStatus, PageRequest.of(0, 15)));
        return "operator/payments";
    }

    @PostMapping("/operator/payments/initiate")
    String initiateMockPayment(
            @RequestParam UUID invoiceId,
            @RequestParam(required = false) BigDecimal amount,
            @RequestParam(required = false) SupportedCurrency currency,
            @RequestParam MockPaymentOutcome simulationOutcome,
            RedirectAttributes redirectAttributes
    ) {
        try {
            var result = paymentProcessingFacade.initiatePayment(new InitiatePaymentCommand(
                    invoiceId, amount, currency, simulationOutcome, "op-pay-" + UUID.randomUUID()
            ));
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Mock payment " + result.payment().paymentReference() + " recorded with status " + result.payment().status()
            );
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/operator/payments";
    }

    @PostMapping("/operator/payments/dunning/process")
    String processDueDunning(RedirectAttributes redirectAttributes) {
        try {
            DunningExecutionSummary summary = dunningManagementFacade.processDueDunning();
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Dunning cycle: retries=" + summary.retriesProcessed()
                            + ", succeeded=" + summary.retriesSucceeded()
                            + ", suspended=" + summary.subscriptionsSuspended()
            );
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/operator/payments";
    }

    @GetMapping("/operator/webhooks")
    String webhooks(
            @RequestParam(required = false) WebhookDeliveryStatus status,
            @RequestParam(defaultValue = "0") int page,
            Model model
    ) {
        model.addAttribute("activeTab", "webhooks");
        model.addAttribute("selectedStatus", status);
        model.addAttribute("statuses", WebhookDeliveryStatus.values());
        model.addAttribute("endpointsPage", webhookEndpointFacade.listAllEndpoints(PageRequest.of(0, 20)));
        model.addAttribute("deliveriesPage", webhookDeliveryFacade.listAllDeliveries(status, PageRequest.of(Math.max(0, page), 15)));
        model.addAttribute("auditLogsPage", auditLogFacade.listAuditLogs(null, PageRequest.of(0, 15)));
        return "operator/webhooks";
    }

    @PostMapping("/operator/webhooks/dispatch")
    String dispatchDueWebhooks(RedirectAttributes redirectAttributes) {
        try {
            WebhookDispatchSummary summary = webhookDeliveryFacade.dispatchDueDeliveries();
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Dispatched webhooks: claimed=" + summary.claimedCount()
                            + ", delivered=" + summary.deliveredCount()
                            + ", retrying=" + summary.scheduledRetryCount()
                            + ", exhausted=" + summary.exhaustedCount()
            );
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/operator/webhooks";
    }

    @PostMapping("/operator/webhooks/deliveries/{deliveryId}/replay")
    String replayWebhookDelivery(@PathVariable UUID deliveryId, RedirectAttributes redirectAttributes) {
        try {
            var replayed = webhookDeliveryFacade.replayDelivery(deliveryId);
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Auditable manual replay finished: status=" + replayed.summary().status()
                            + (replayed.summary().lastHttpStatus() != null ? " (HTTP " + replayed.summary().lastHttpStatus() + ")" : "")
            );
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/operator/webhooks";
    }

    @GetMapping("/operator/catalog")
    String catalog(Model model) {
        model.addAttribute("activeTab", "catalog");
        model.addAttribute("metrics", metricCatalogFacade.listMetrics(PageRequest.of(0, 30)).getContent());
        List<PricingPlanSummary> plans = planCatalogFacade.listPlans(PageRequest.of(0, 30)).getContent();
        model.addAttribute("plans", plans);
        List<PlanVersionDetail> allVersions = new ArrayList<>();
        for (PricingPlanSummary plan : plans) {
            allVersions.addAll(planCatalogFacade.listPlanVersions(plan.id()));
        }
        model.addAttribute("planVersions", allVersions);
        return "operator/catalog";
    }

    @PostMapping("/operator/catalog/versions/{versionId}/publish")
    String publishPlanVersion(@PathVariable UUID versionId, RedirectAttributes redirectAttributes) {
        try {
            PlanVersionDetail published = planCatalogFacade.publishVersion(versionId);
            redirectAttributes.addFlashAttribute("successMessage", "Published " + published.planCode() + " v" + published.versionNumber());
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/operator/catalog";
    }

    @PostMapping("/operator/catalog/versions/{versionId}/clone")
    String clonePlanVersion(@PathVariable UUID versionId, RedirectAttributes redirectAttributes) {
        try {
            PlanVersionDetail cloned = planCatalogFacade.cloneVersionToDraft(versionId, Instant.now());
            redirectAttributes.addFlashAttribute("successMessage", "Cloned draft " + cloned.planCode() + " v" + cloned.versionNumber());
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/operator/catalog";
    }

    @GetMapping("/operator/simulation")
    String operatorSimulationForm(Model model) {
        model.addAttribute("activeTab", "simulation");
        populateOperatorSimulationOptions(model);
        return "operator/simulation";
    }

    @PostMapping("/operator/simulation")
    String runOperatorSimulation(
            @RequestParam UUID originalInvoiceId,
            @RequestParam UUID targetPlanVersionId,
            @RequestParam(required = false) Integer seatQuantityOverride,
            Model model
    ) {
        model.addAttribute("activeTab", "simulation");
        populateOperatorSimulationOptions(model);
        model.addAttribute("selectedInvoiceId", originalInvoiceId);
        model.addAttribute("selectedPlanVersionId", targetPlanVersionId);
        model.addAttribute("seatQuantityOverride", seatQuantityOverride);
        try {
            HistoricalInvoiceComparisonResult comparison = historicalSimulationFacade.simulateHistoricalInvoice(
                    new HistoricalInvoiceSimulationRequest(
                            originalInvoiceId,
                            null,
                            null,
                            null,
                            null,
                            targetPlanVersionId,
                            seatQuantityOverride
                    )
            );
            model.addAttribute("comparison", comparison);
        } catch (RuntimeException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
        }
        return "operator/simulation";
    }

    private void populateOperatorSimulationOptions(Model model) {
        model.addAttribute("invoices", invoiceManagementFacade.listInvoices(null, PageRequest.of(0, 40)).getContent().stream()
                .filter(inv -> inv.status() != InvoiceStatus.VOID)
                .toList());
        List<PlanVersionDetail> allVersions = new ArrayList<>();
        for (PricingPlanSummary plan : planCatalogFacade.listPlans(PageRequest.of(0, 50)).getContent()) {
            allVersions.addAll(planCatalogFacade.listPlanVersions(plan.id()));
        }
        model.addAttribute("planVersions", allVersions);
    }
}
