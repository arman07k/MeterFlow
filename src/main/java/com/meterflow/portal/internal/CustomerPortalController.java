package com.meterflow.portal.internal;

import com.meterflow.billing.api.CreditNoteFacade;
import com.meterflow.billing.api.CustomerCreditBalance;
import com.meterflow.billing.api.HistoricalInvoiceComparisonResult;
import com.meterflow.billing.api.HistoricalInvoiceSimulationFacade;
import com.meterflow.billing.api.HistoricalInvoiceSimulationRequest;
import com.meterflow.billing.api.InvoiceDetail;
import com.meterflow.billing.api.InvoiceManagementFacade;
import com.meterflow.billing.api.InvoiceStatus;
import com.meterflow.billing.api.InvoiceSummary;
import com.meterflow.billing.api.PrepaidCreditFacade;
import com.meterflow.catalog.api.BillableMetricDefinition;
import com.meterflow.catalog.api.MetricCatalogFacade;
import com.meterflow.catalog.api.PlanCatalogFacade;
import com.meterflow.catalog.api.PlanVersionDetail;
import com.meterflow.catalog.api.PlanVersionStatus;
import com.meterflow.catalog.api.PricingPlanSummary;
import com.meterflow.identity.api.AuthenticatedActor;
import com.meterflow.identity.api.CustomerAccessGuard;
import com.meterflow.identity.api.CustomerSummary;
import com.meterflow.identity.api.IdentityQueryFacade;
import com.meterflow.identity.api.UnauthorizedCustomerAccessException;
import com.meterflow.metering.api.LiveUsageEstimateRequest;
import com.meterflow.metering.api.LiveUsageEstimateResult;
import com.meterflow.metering.api.UsageQueryFacade;
import com.meterflow.notifications.api.NotificationFacade;
import com.meterflow.payments.api.PaymentQueryFacade;
import com.meterflow.subscriptions.api.SubscriptionHistory;
import com.meterflow.subscriptions.api.SubscriptionManagementFacade;
import com.meterflow.subscriptions.api.SubscriptionQueryFacade;
import com.meterflow.subscriptions.api.SubscriptionSummary;
import com.meterflow.webhooks.api.RegisterWebhookEndpointCommand;
import com.meterflow.webhooks.api.UpdateWebhookEndpointCommand;
import com.meterflow.webhooks.api.WebhookDeliveryDetail;
import com.meterflow.webhooks.api.WebhookDeliveryFacade;
import com.meterflow.webhooks.api.WebhookEndpointFacade;
import com.meterflow.webhooks.api.WebhookEndpointSummary;
import com.meterflow.webhooks.api.WebhookEventTypes;
import com.meterflow.webhooks.api.WebhookSecretDisclosure;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Server-rendered Thymeleaf Customer Portal controller (/customer/**).
 * Strictly enforces authenticated customer ownership via CustomerAccessGuard on every route.
 */
@Controller
@RequestMapping("/customer")
class CustomerPortalController {

    private final CustomerAccessGuard accessGuard;
    private final IdentityQueryFacade identityQueryFacade;
    private final SubscriptionQueryFacade subscriptionQueryFacade;
    private final SubscriptionManagementFacade subscriptionManagementFacade;
    private final PlanCatalogFacade planCatalogFacade;
    private final MetricCatalogFacade metricCatalogFacade;
    private final UsageQueryFacade usageQueryFacade;
    private final InvoiceManagementFacade invoiceManagementFacade;
    private final PrepaidCreditFacade prepaidCreditFacade;
    private final CreditNoteFacade creditNoteFacade;
    private final HistoricalInvoiceSimulationFacade historicalSimulationFacade;
    private final PaymentQueryFacade paymentQueryFacade;
    private final NotificationFacade notificationFacade;
    private final WebhookEndpointFacade webhookEndpointFacade;
    private final WebhookDeliveryFacade webhookDeliveryFacade;

    CustomerPortalController(
            CustomerAccessGuard accessGuard,
            IdentityQueryFacade identityQueryFacade,
            SubscriptionQueryFacade subscriptionQueryFacade,
            SubscriptionManagementFacade subscriptionManagementFacade,
            PlanCatalogFacade planCatalogFacade,
            MetricCatalogFacade metricCatalogFacade,
            UsageQueryFacade usageQueryFacade,
            InvoiceManagementFacade invoiceManagementFacade,
            PrepaidCreditFacade prepaidCreditFacade,
            CreditNoteFacade creditNoteFacade,
            HistoricalInvoiceSimulationFacade historicalSimulationFacade,
            PaymentQueryFacade paymentQueryFacade,
            NotificationFacade notificationFacade,
            WebhookEndpointFacade webhookEndpointFacade,
            WebhookDeliveryFacade webhookDeliveryFacade
    ) {
        this.accessGuard = accessGuard;
        this.identityQueryFacade = identityQueryFacade;
        this.subscriptionQueryFacade = subscriptionQueryFacade;
        this.subscriptionManagementFacade = subscriptionManagementFacade;
        this.planCatalogFacade = planCatalogFacade;
        this.metricCatalogFacade = metricCatalogFacade;
        this.usageQueryFacade = usageQueryFacade;
        this.invoiceManagementFacade = invoiceManagementFacade;
        this.prepaidCreditFacade = prepaidCreditFacade;
        this.creditNoteFacade = creditNoteFacade;
        this.historicalSimulationFacade = historicalSimulationFacade;
        this.paymentQueryFacade = paymentQueryFacade;
        this.notificationFacade = notificationFacade;
        this.webhookEndpointFacade = webhookEndpointFacade;
        this.webhookDeliveryFacade = webhookDeliveryFacade;
    }

    @GetMapping({ "", "/", "/dashboard" })
    String dashboard(@RequestParam(required = false) UUID customerId, Model model) {
        CustomerSummary customer = resolveAuthorizedCustomer(customerId, model, "dashboard");
        Optional<SubscriptionSummary> subscription = subscriptionQueryFacade.findOpenSubscriptionByCustomerId(customer.id());
        model.addAttribute("subscription", subscription.orElse(null));
        subscription.flatMap(sub -> planCatalogFacade.findPlanVersionById(sub.currentPlanVersionId()))
                .ifPresent(pv -> model.addAttribute("planVersion", pv));

        LiveUsageEstimateResult liveEstimate = null;
        if (subscription.isPresent()) {
            try {
                liveEstimate = usageQueryFacade.estimateLiveUsage(new LiveUsageEstimateRequest(customer.id(), List.of()));
            } catch (RuntimeException ignored) {
                liveEstimate = null;
            }
        }
        model.addAttribute("liveEstimate", liveEstimate);
        CustomerCreditBalance creditBalance = prepaidCreditFacade.getCustomerCreditBalance(customer.id(), customer.currency());
        model.addAttribute("creditBalance", creditBalance);
        model.addAttribute("recentInvoices", invoiceManagementFacade.listCustomerInvoices(customer.id(), null, PageRequest.of(0, 5)).getContent());
        model.addAttribute("recentPayments", paymentQueryFacade.listCustomerPayments(customer.id(), PageRequest.of(0, 5)).getContent());
        model.addAttribute("recentNotifications", notificationFacade.listCustomerNotifications(customer.id(), false, PageRequest.of(0, 5)).getContent());
        return "customer/dashboard";
    }

    @GetMapping("/usage")
    String usage(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID metricId,
            @RequestParam(defaultValue = "0") int page,
            Model model
    ) {
        CustomerSummary customer = resolveAuthorizedCustomer(customerId, model, "usage");
        List<BillableMetricDefinition> metrics = metricCatalogFacade
                .listCustomerAccessibleMetrics(customer.id(), PageRequest.of(0, 50))
                .getContent();
        model.addAttribute("metrics", metrics);
        model.addAttribute("selectedMetricId", metricId);

        LiveUsageEstimateResult liveEstimate = null;
        try {
            liveEstimate = usageQueryFacade.estimateLiveUsage(new LiveUsageEstimateRequest(customer.id(), List.of()));
        } catch (RuntimeException ignored) {
            liveEstimate = null;
        }
        model.addAttribute("liveEstimate", liveEstimate);
        model.addAttribute("eventsPage", usageQueryFacade.listCustomerEvents(
                customer.id(),
                metricId,
                null,
                null,
                PageRequest.of(Math.max(0, page), 20)
        ));
        return "customer/usage";
    }

    @GetMapping("/subscription")
    String subscription(@RequestParam(required = false) UUID customerId, Model model) {
        CustomerSummary customer = resolveAuthorizedCustomer(customerId, model, "subscription");
        Optional<SubscriptionSummary> openSub = subscriptionQueryFacade.findOpenSubscriptionByCustomerId(customer.id());
        model.addAttribute("subscription", openSub.orElse(null));
        if (openSub.isPresent()) {
            SubscriptionHistory history = subscriptionQueryFacade.getSubscriptionHistory(openSub.get().id());
            model.addAttribute("history", history);
            planCatalogFacade.findPlanVersionById(openSub.get().currentPlanVersionId())
                    .ifPresent(pv -> model.addAttribute("planVersion", pv));
        }
        model.addAttribute("allSubscriptions", subscriptionQueryFacade.listCustomerSubscriptions(customer.id(), PageRequest.of(0, 20)).getContent());
        return "customer/subscription";
    }

    @PostMapping("/subscription/pause")
    String pauseSubscription(
            @RequestParam UUID customerId,
            @RequestParam UUID subscriptionId,
            RedirectAttributes redirectAttributes
    ) {
        accessGuard.requireCustomerAccess(customerId);
        verifySubscriptionOwnership(customerId, subscriptionId);
        try {
            subscriptionManagementFacade.pauseSubscription(subscriptionId, "portal-pause-" + UUID.randomUUID());
            redirectAttributes.addFlashAttribute("successMessage", "Subscription paused.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/customer/subscription?customerId=" + customerId;
    }

    @PostMapping("/subscription/resume")
    String resumeSubscription(
            @RequestParam UUID customerId,
            @RequestParam UUID subscriptionId,
            RedirectAttributes redirectAttributes
    ) {
        accessGuard.requireCustomerAccess(customerId);
        verifySubscriptionOwnership(customerId, subscriptionId);
        try {
            subscriptionManagementFacade.resumeSubscription(subscriptionId, "portal-resume-" + UUID.randomUUID());
            redirectAttributes.addFlashAttribute("successMessage", "Subscription resumed.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/customer/subscription?customerId=" + customerId;
    }

    @PostMapping("/subscription/cancel-at-period-end")
    String cancelAtPeriodEnd(
            @RequestParam UUID customerId,
            @RequestParam UUID subscriptionId,
            RedirectAttributes redirectAttributes
    ) {
        accessGuard.requireCustomerAccess(customerId);
        verifySubscriptionOwnership(customerId, subscriptionId);
        try {
            subscriptionManagementFacade.cancelAtPeriodEnd(subscriptionId, "portal-cancel-" + UUID.randomUUID());
            redirectAttributes.addFlashAttribute("successMessage", "Subscription scheduled for cancellation at period end.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/customer/subscription?customerId=" + customerId;
    }

    @PostMapping("/subscription/cancel-scheduled-change")
    String cancelScheduledChange(
            @RequestParam UUID customerId,
            @RequestParam UUID subscriptionId,
            RedirectAttributes redirectAttributes
    ) {
        accessGuard.requireCustomerAccess(customerId);
        verifySubscriptionOwnership(customerId, subscriptionId);
        try {
            subscriptionManagementFacade.cancelScheduledPlanChange(subscriptionId, "portal-cancel-sched-" + UUID.randomUUID());
            redirectAttributes.addFlashAttribute("successMessage", "Scheduled plan change cancelled.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/customer/subscription?customerId=" + customerId;
    }

    @GetMapping("/invoices")
    String invoices(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) InvoiceStatus status,
            @RequestParam(defaultValue = "0") int page,
            Model model
    ) {
        CustomerSummary customer = resolveAuthorizedCustomer(customerId, model, "invoices");
        model.addAttribute("selectedStatus", status);
        model.addAttribute("statuses", InvoiceStatus.values());
        model.addAttribute("invoicesPage", invoiceManagementFacade.listCustomerInvoices(
                customer.id(),
                status,
                PageRequest.of(Math.max(0, page), 15)
        ));
        return "customer/invoices";
    }

    @GetMapping("/invoices/{invoiceId}")
    String invoiceDetail(
            @PathVariable UUID invoiceId,
            @RequestParam(required = false) UUID customerId,
            Model model
    ) {
        InvoiceDetail detail = invoiceManagementFacade.getInvoiceDetail(invoiceId);
        CustomerSummary customer = resolveAuthorizedCustomer(detail.invoice().customerId(), model, "invoices");
        if (customerId != null && !customerId.equals(customer.id())) {
            throw new UnauthorizedCustomerAccessException("Invoice does not belong to customer");
        }
        model.addAttribute("invoiceDetail", detail);
        model.addAttribute("creditNotes", creditNoteFacade.listCreditNotesForInvoice(invoiceId));
        model.addAttribute("payments", paymentQueryFacade.listPaymentsForInvoice(invoiceId));
        return "customer/invoice-detail";
    }

    @GetMapping("/payments")
    String payments(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            Model model
    ) {
        CustomerSummary customer = resolveAuthorizedCustomer(customerId, model, "payments");
        model.addAttribute("paymentsPage", paymentQueryFacade.listCustomerPayments(
                customer.id(),
                PageRequest.of(Math.max(0, page), 15)
        ));
        model.addAttribute("dunningPage", paymentQueryFacade.listCustomerDunningCases(
                customer.id(),
                PageRequest.of(0, 10)
        ));
        return "customer/payments";
    }

    @GetMapping("/credits")
    String credits(@RequestParam(required = false) UUID customerId, Model model) {
        CustomerSummary customer = resolveAuthorizedCustomer(customerId, model, "credits");
        model.addAttribute("balance", prepaidCreditFacade.getCustomerCreditBalance(customer.id(), customer.currency()));
        model.addAttribute("grants", prepaidCreditFacade.listCustomerCreditGrants(customer.id(), PageRequest.of(0, 25)).getContent());
        model.addAttribute("transactions", prepaidCreditFacade.listCustomerCreditTransactions(customer.id(), PageRequest.of(0, 25)).getContent());
        model.addAttribute("creditNotes", creditNoteFacade.listCustomerCreditNotes(customer.id(), PageRequest.of(0, 25)).getContent());
        return "customer/credits";
    }

    @GetMapping("/notifications")
    String notifications(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) Boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page,
            Model model
    ) {
        CustomerSummary customer = resolveAuthorizedCustomer(customerId, model, "notifications");
        model.addAttribute("unreadOnly", Boolean.TRUE.equals(unreadOnly));
        model.addAttribute("notificationsPage", notificationFacade.listCustomerNotifications(
                customer.id(),
                unreadOnly,
                PageRequest.of(Math.max(0, page), 20)
        ));
        return "customer/notifications";
    }

    @PostMapping("/notifications/{notificationId}/read")
    String markNotificationRead(
            @PathVariable UUID notificationId,
            @RequestParam UUID customerId,
            RedirectAttributes redirectAttributes
    ) {
        notificationFacade.markAsReadForCustomer(customerId, notificationId);
        redirectAttributes.addFlashAttribute("successMessage", "Notification marked as read.");
        return "redirect:/customer/notifications?customerId=" + customerId;
    }

    @PostMapping("/notifications/read-all")
    String markAllNotificationsRead(
            @RequestParam UUID customerId,
            RedirectAttributes redirectAttributes
    ) {
        accessGuard.requireCustomerAccess(customerId);
        int count = notificationFacade.markAllAsRead(customerId);
        redirectAttributes.addFlashAttribute("successMessage", "Marked " + count + " notifications as read.");
        return "redirect:/customer/notifications?customerId=" + customerId;
    }

    @GetMapping("/webhooks")
    String webhooks(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            Model model
    ) {
        CustomerSummary customer = resolveAuthorizedCustomer(customerId, model, "webhooks");
        model.addAttribute("endpoints", webhookEndpointFacade.listCustomerEndpoints(customer.id()));
        model.addAttribute("deliveriesPage", webhookDeliveryFacade.listCustomerDeliveries(
                customer.id(),
                null,
                PageRequest.of(Math.max(0, page), 15)
        ));
        List<String> supportedEvents = new ArrayList<>(WebhookEventTypes.SUPPORTED);
        supportedEvents.sort(Comparator.naturalOrder());
        model.addAttribute("supportedEvents", supportedEvents);
        return "customer/webhooks";
    }

    @PostMapping("/webhooks/endpoints")
    String registerWebhookEndpoint(
            @RequestParam UUID customerId,
            @RequestParam String name,
            @RequestParam String targetUrl,
            @RequestParam(required = false) Set<String> subscribedEvents,
            RedirectAttributes redirectAttributes
    ) {
        accessGuard.requireCustomerAccess(customerId);
        try {
            WebhookSecretDisclosure disclosure = webhookEndpointFacade.registerEndpoint(
                    new RegisterWebhookEndpointCommand(customerId, name, targetUrl, subscribedEvents)
            );
            redirectAttributes.addFlashAttribute("successMessage", "Webhook endpoint registered.");
            redirectAttributes.addFlashAttribute("oneTimeSecret", disclosure.signingSecret());
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/customer/webhooks?customerId=" + customerId;
    }

    @PostMapping("/webhooks/endpoints/{endpointId}/toggle")
    String toggleWebhookEndpoint(
            @PathVariable UUID endpointId,
            @RequestParam UUID customerId,
            @RequestParam boolean active,
            RedirectAttributes redirectAttributes
    ) {
        accessGuard.requireCustomerAccess(customerId);
        try {
            WebhookEndpointSummary existing = webhookEndpointFacade.getEndpoint(endpointId);
            if (!existing.customerId().equals(customerId)) {
                throw new UnauthorizedCustomerAccessException("Endpoint does not belong to customer");
            }
            webhookEndpointFacade.updateEndpoint(endpointId, new UpdateWebhookEndpointCommand(null, null, null, active));
            redirectAttributes.addFlashAttribute("successMessage", active ? "Webhook endpoint enabled." : "Webhook endpoint disabled.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/customer/webhooks?customerId=" + customerId;
    }

    @PostMapping("/webhooks/endpoints/{endpointId}/rotate-secret")
    String rotateWebhookSecret(
            @PathVariable UUID endpointId,
            @RequestParam UUID customerId,
            RedirectAttributes redirectAttributes
    ) {
        accessGuard.requireCustomerAccess(customerId);
        try {
            WebhookEndpointSummary existing = webhookEndpointFacade.getEndpoint(endpointId);
            if (!existing.customerId().equals(customerId)) {
                throw new UnauthorizedCustomerAccessException("Endpoint does not belong to customer");
            }
            WebhookSecretDisclosure rotated = webhookEndpointFacade.rotateSecret(endpointId);
            redirectAttributes.addFlashAttribute("successMessage", "Webhook signing secret rotated.");
            redirectAttributes.addFlashAttribute("oneTimeSecret", rotated.signingSecret());
            redirectAttributes.addFlashAttribute("previousSecretExpiresAt", rotated.previousSecretExpiresAt());
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/customer/webhooks?customerId=" + customerId;
    }

    @PostMapping("/webhooks/endpoints/{endpointId}/delete")
    String deleteWebhookEndpoint(
            @PathVariable UUID endpointId,
            @RequestParam UUID customerId,
            RedirectAttributes redirectAttributes
    ) {
        accessGuard.requireCustomerAccess(customerId);
        try {
            WebhookEndpointSummary existing = webhookEndpointFacade.getEndpoint(endpointId);
            if (!existing.customerId().equals(customerId)) {
                throw new UnauthorizedCustomerAccessException("Endpoint does not belong to customer");
            }
            webhookEndpointFacade.deleteEndpoint(endpointId);
            redirectAttributes.addFlashAttribute("successMessage", "Webhook endpoint deleted.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/customer/webhooks?customerId=" + customerId;
    }

    @GetMapping("/webhooks/deliveries/{deliveryId}")
    String webhookDeliveryDetail(
            @PathVariable UUID deliveryId,
            @RequestParam(required = false) UUID customerId,
            Model model
    ) {
        WebhookDeliveryDetail detail = webhookDeliveryFacade.getDeliveryDetail(deliveryId);
        resolveAuthorizedCustomer(detail.summary().customerId(), model, "webhooks");
        model.addAttribute("deliveryDetail", detail);
        return "customer/webhook-delivery-detail";
    }

    @PostMapping("/webhooks/deliveries/{deliveryId}/replay")
    String replayWebhookDelivery(
            @PathVariable UUID deliveryId,
            @RequestParam UUID customerId,
            RedirectAttributes redirectAttributes
    ) {
        accessGuard.requireCustomerAccess(customerId);
        try {
            WebhookDeliveryDetail existing = webhookDeliveryFacade.getDeliveryDetail(deliveryId);
            if (!existing.summary().customerId().equals(customerId)) {
                throw new UnauthorizedCustomerAccessException("Delivery does not belong to customer");
            }
            WebhookDeliveryDetail replayed = webhookDeliveryFacade.replayDelivery(deliveryId);
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Manual replay completed with status " + replayed.summary().status()
                            + (replayed.summary().lastHttpStatus() != null ? " (HTTP " + replayed.summary().lastHttpStatus() + ")" : "")
            );
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/customer/webhooks/deliveries/" + deliveryId + "?customerId=" + customerId;
    }

    @GetMapping("/simulation")
    String simulationForm(@RequestParam(required = false) UUID customerId, Model model) {
        CustomerSummary customer = resolveAuthorizedCustomer(customerId, model, "simulation");
        populateSimulationOptions(customer, model);
        return "customer/simulation";
    }

    @PostMapping("/simulation")
    String runSimulation(
            @RequestParam UUID customerId,
            @RequestParam UUID originalInvoiceId,
            @RequestParam UUID targetPlanVersionId,
            @RequestParam(required = false) Integer seatQuantityOverride,
            Model model
    ) {
        CustomerSummary customer = resolveAuthorizedCustomer(customerId, model, "simulation");
        populateSimulationOptions(customer, model);
        model.addAttribute("selectedInvoiceId", originalInvoiceId);
        model.addAttribute("selectedPlanVersionId", targetPlanVersionId);
        model.addAttribute("seatQuantityOverride", seatQuantityOverride);
        try {
            HistoricalInvoiceComparisonResult comparison = historicalSimulationFacade.simulateHistoricalInvoice(
                    new HistoricalInvoiceSimulationRequest(
                            originalInvoiceId,
                            customer.id(),
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
        return "customer/simulation";
    }

    private void populateSimulationOptions(CustomerSummary customer, Model model) {
        Page<InvoiceSummary> allInvoices = invoiceManagementFacade.listCustomerInvoices(
                customer.id(),
                null,
                PageRequest.of(0, 30)
        );
        List<InvoiceSummary> eligibleInvoices = allInvoices.getContent().stream()
                .filter(inv -> inv.status() != InvoiceStatus.VOID)
                .toList();
        model.addAttribute("eligibleInvoices", eligibleInvoices);

        List<PlanVersionDetail> publishedVersions = new ArrayList<>();
        for (PricingPlanSummary plan : planCatalogFacade.listPlans(PageRequest.of(0, 50)).getContent()) {
            if (plan.currency() != customer.currency()) {
                continue;
            }
            for (PlanVersionDetail version : planCatalogFacade.listPlanVersions(plan.id())) {
                if (version.status() == PlanVersionStatus.PUBLISHED) {
                    publishedVersions.add(version);
                }
            }
        }
        model.addAttribute("publishedPlanVersions", publishedVersions);
    }

    private void verifySubscriptionOwnership(UUID customerId, UUID subscriptionId) {
        SubscriptionSummary sub = subscriptionQueryFacade.findSubscriptionById(subscriptionId)
                .orElseThrow(() -> new UnauthorizedCustomerAccessException("Subscription not found"));
        if (!sub.customerId().equals(customerId)) {
            throw new UnauthorizedCustomerAccessException("Subscription does not belong to customer");
        }
    }

    private CustomerSummary resolveAuthorizedCustomer(UUID requestedCustomerId, Model model, String activeTab) {
        AuthenticatedActor actor = accessGuard.requireAuthenticatedActor();
        Set<UUID> authorizedIds = identityQueryFacade.findAuthorizedCustomerIds(actor.userId());
        if (authorizedIds.isEmpty()) {
            throw new UnauthorizedCustomerAccessException("No active customer memberships found for user");
        }
        UUID resolvedId = requestedCustomerId != null ? requestedCustomerId : authorizedIds.iterator().next();
        accessGuard.requireCustomerAccess(resolvedId);
        CustomerSummary customer = identityQueryFacade.findCustomerById(resolvedId)
                .orElseThrow(() -> new UnauthorizedCustomerAccessException("Customer not found"));

        long unreadCount = notificationFacade.countUnreadNotifications(customer.id());
        model.addAttribute("customer", customer);
        model.addAttribute("unreadCount", unreadCount);
        model.addAttribute("activeTab", activeTab);
        return customer;
    }
}
