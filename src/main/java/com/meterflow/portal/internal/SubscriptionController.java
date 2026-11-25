package com.meterflow.portal.internal;

import com.meterflow.identity.api.CustomerAccessGuard;
import com.meterflow.identity.api.UnauthorizedCustomerAccessException;
import com.meterflow.shared.BillingInterval;
import com.meterflow.shared.SupportedCurrency;
import com.meterflow.subscriptions.api.CreateSubscriptionCommand;
import com.meterflow.subscriptions.api.ImmediatePlanChangeCommand;
import com.meterflow.subscriptions.api.ImmediatePlanChangeResult;
import com.meterflow.subscriptions.api.MaintenanceProcessingSummary;
import com.meterflow.subscriptions.api.SchedulePlanChangeCommand;
import com.meterflow.subscriptions.api.ScheduledPlanChangeRecord;
import com.meterflow.subscriptions.api.SubscriptionHistory;
import com.meterflow.subscriptions.api.SubscriptionMaintenanceFacade;
import com.meterflow.subscriptions.api.SubscriptionManagementFacade;
import com.meterflow.subscriptions.api.SubscriptionNotFoundException;
import com.meterflow.subscriptions.api.SubscriptionQueryFacade;
import com.meterflow.subscriptions.api.SubscriptionSummary;
import com.meterflow.subscriptions.api.TrialConversionPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin REST controller exposing Subscription Management capabilities over {@code subscriptions :: api}
 * with strict customer isolation via {@link CustomerAccessGuard} (FR4, ADR-006, ADR-012).
 */
@RestController
class SubscriptionController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final SubscriptionManagementFacade managementFacade;
    private final SubscriptionQueryFacade queryFacade;
    private final SubscriptionMaintenanceFacade maintenanceFacade;
    private final CustomerAccessGuard customerAccessGuard;

    SubscriptionController(
            SubscriptionManagementFacade managementFacade,
            SubscriptionQueryFacade queryFacade,
            SubscriptionMaintenanceFacade maintenanceFacade,
            CustomerAccessGuard customerAccessGuard
    ) {
        this.managementFacade = managementFacade;
        this.queryFacade = queryFacade;
        this.maintenanceFacade = maintenanceFacade;
        this.customerAccessGuard = customerAccessGuard;
    }

    // ------------------------------------------------------------------------
    // Operator / Admin Endpoints (/api/v1/subscriptions/**)
    // ------------------------------------------------------------------------

    @PostMapping("/api/v1/subscriptions")
    ResponseEntity<SubscriptionSummary> createSubscription(
            @Valid @RequestBody CreateSubscriptionRequest request,
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String headerIdempotencyKey
    ) {
        SupportedCurrency currency = request.currency() != null && !request.currency().isBlank()
                ? SupportedCurrency.fromCode(request.currency())
                : null;
        SubscriptionSummary created = managementFacade.createSubscription(new CreateSubscriptionCommand(
                request.customerId(),
                request.planVersionId(),
                currency,
                request.billingInterval(),
                request.billingTimezone(),
                request.seatQuantity(),
                request.startAt(),
                request.trialEndAt(),
                request.trialConversionPolicy(),
                resolveIdempotencyKey(headerIdempotencyKey, request.idempotencyKey())
        ));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/api/v1/subscriptions/{subscriptionId}")
    SubscriptionSummary getSubscription(@PathVariable UUID subscriptionId) {
        return queryFacade.findSubscriptionById(subscriptionId)
                .orElseThrow(() -> new SubscriptionNotFoundException("Subscription not found"));
    }

    @GetMapping("/api/v1/subscriptions/customer/{customerId}")
    PageResponse<SubscriptionSummary> listSubscriptionsForCustomerAdmin(
            @PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PageResponse.from(queryFacade.listCustomerSubscriptions(customerId, pageable));
    }

    @GetMapping("/api/v1/subscriptions/{subscriptionId}/history")
    SubscriptionHistory getSubscriptionHistory(@PathVariable UUID subscriptionId) {
        return queryFacade.getSubscriptionHistory(subscriptionId);
    }

    @PostMapping("/api/v1/subscriptions/{subscriptionId}/trial/convert")
    SubscriptionSummary convertTrial(
            @PathVariable UUID subscriptionId,
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String headerIdempotencyKey
    ) {
        return managementFacade.convertTrial(subscriptionId, headerIdempotencyKey);
    }

    @PostMapping("/api/v1/subscriptions/{subscriptionId}/trial/cancel")
    SubscriptionSummary cancelTrial(
            @PathVariable UUID subscriptionId,
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String headerIdempotencyKey
    ) {
        return managementFacade.cancelTrial(subscriptionId, headerIdempotencyKey);
    }

    @PostMapping("/api/v1/subscriptions/{subscriptionId}/plan-changes/immediate")
    ImmediatePlanChangeResult changePlanImmediately(
            @PathVariable UUID subscriptionId,
            @Valid @RequestBody ImmediatePlanChangeRequest request,
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String headerIdempotencyKey
    ) {
        return managementFacade.changePlanImmediately(subscriptionId, new ImmediatePlanChangeCommand(
                request.targetPlanVersionId(),
                request.targetSeatQuantity(),
                request.effectiveAt(),
                resolveIdempotencyKey(headerIdempotencyKey, request.idempotencyKey())
        ));
    }

    @PostMapping("/api/v1/subscriptions/{subscriptionId}/plan-changes/scheduled")
    ResponseEntity<ScheduledPlanChangeRecord> schedulePlanChange(
            @PathVariable UUID subscriptionId,
            @Valid @RequestBody SchedulePlanChangeRequest request,
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String headerIdempotencyKey
    ) {
        ScheduledPlanChangeRecord scheduled = managementFacade.schedulePlanChange(
                subscriptionId,
                new SchedulePlanChangeCommand(
                        request.targetPlanVersionId(),
                        request.targetSeatQuantity(),
                        Boolean.TRUE.equals(request.replaceExisting()),
                        resolveIdempotencyKey(headerIdempotencyKey, request.idempotencyKey())
                )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(scheduled);
    }

    @DeleteMapping("/api/v1/subscriptions/{subscriptionId}/plan-changes/scheduled")
    ScheduledPlanChangeRecord cancelScheduledPlanChange(
            @PathVariable UUID subscriptionId,
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String headerIdempotencyKey
    ) {
        return managementFacade.cancelScheduledPlanChange(subscriptionId, headerIdempotencyKey);
    }

    @PostMapping("/api/v1/subscriptions/{subscriptionId}/pause")
    SubscriptionSummary pauseSubscription(
            @PathVariable UUID subscriptionId,
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String headerIdempotencyKey
    ) {
        return managementFacade.pauseSubscription(subscriptionId, headerIdempotencyKey);
    }

    @PostMapping("/api/v1/subscriptions/{subscriptionId}/resume")
    SubscriptionSummary resumeSubscription(
            @PathVariable UUID subscriptionId,
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String headerIdempotencyKey
    ) {
        return managementFacade.resumeSubscription(subscriptionId, headerIdempotencyKey);
    }

    @PostMapping("/api/v1/subscriptions/{subscriptionId}/cancel")
    SubscriptionSummary cancelImmediately(
            @PathVariable UUID subscriptionId,
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String headerIdempotencyKey
    ) {
        return managementFacade.cancelImmediately(subscriptionId, headerIdempotencyKey);
    }

    @PostMapping("/api/v1/subscriptions/{subscriptionId}/cancel-at-period-end")
    SubscriptionSummary cancelAtPeriodEnd(
            @PathVariable UUID subscriptionId,
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String headerIdempotencyKey
    ) {
        return managementFacade.cancelAtPeriodEnd(subscriptionId, headerIdempotencyKey);
    }

    @PostMapping("/api/v1/subscriptions/maintenance/run")
    MaintenanceProcessingSummary runMaintenance() {
        return maintenanceFacade.processDueOperations();
    }

    // ------------------------------------------------------------------------
    // Customer-Scoped Endpoints (/api/v1/customer/{customerId}/subscriptions/**)
    // ------------------------------------------------------------------------

    @GetMapping("/api/v1/customer/{customerId}/subscriptions")
    PageResponse<SubscriptionSummary> listCustomerSubscriptions(
            @PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        customerAccessGuard.requireCustomerAccess(customerId);
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PageResponse.from(queryFacade.listCustomerSubscriptions(customerId, pageable));
    }

    @GetMapping("/api/v1/customer/{customerId}/subscriptions/{subscriptionId}")
    SubscriptionSummary getCustomerSubscription(
            @PathVariable UUID customerId,
            @PathVariable UUID subscriptionId
    ) {
        customerAccessGuard.requireCustomerAccess(customerId);
        return requireOwnedSubscription(customerId, subscriptionId);
    }

    @GetMapping("/api/v1/customer/{customerId}/subscriptions/{subscriptionId}/history")
    SubscriptionHistory getCustomerSubscriptionHistory(
            @PathVariable UUID customerId,
            @PathVariable UUID subscriptionId
    ) {
        customerAccessGuard.requireCustomerAccess(customerId);
        requireOwnedSubscription(customerId, subscriptionId);
        return queryFacade.getSubscriptionHistory(subscriptionId);
    }

    @PostMapping("/api/v1/customer/{customerId}/subscriptions/{subscriptionId}/plan-changes/scheduled")
    ResponseEntity<ScheduledPlanChangeRecord> scheduleCustomerPlanChange(
            @PathVariable UUID customerId,
            @PathVariable UUID subscriptionId,
            @Valid @RequestBody SchedulePlanChangeRequest request,
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String headerIdempotencyKey
    ) {
        customerAccessGuard.requireCustomerAccess(customerId);
        requireOwnedSubscription(customerId, subscriptionId);
        ScheduledPlanChangeRecord scheduled = managementFacade.schedulePlanChange(
                subscriptionId,
                new SchedulePlanChangeCommand(
                        request.targetPlanVersionId(),
                        request.targetSeatQuantity(),
                        Boolean.TRUE.equals(request.replaceExisting()),
                        resolveIdempotencyKey(headerIdempotencyKey, request.idempotencyKey())
                )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(scheduled);
    }

    @DeleteMapping("/api/v1/customer/{customerId}/subscriptions/{subscriptionId}/plan-changes/scheduled")
    ScheduledPlanChangeRecord cancelCustomerScheduledPlanChange(
            @PathVariable UUID customerId,
            @PathVariable UUID subscriptionId,
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String headerIdempotencyKey
    ) {
        customerAccessGuard.requireCustomerAccess(customerId);
        requireOwnedSubscription(customerId, subscriptionId);
        return managementFacade.cancelScheduledPlanChange(subscriptionId, headerIdempotencyKey);
    }

    @PostMapping("/api/v1/customer/{customerId}/subscriptions/{subscriptionId}/cancel-at-period-end")
    SubscriptionSummary cancelCustomerSubscriptionAtPeriodEnd(
            @PathVariable UUID customerId,
            @PathVariable UUID subscriptionId,
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String headerIdempotencyKey
    ) {
        customerAccessGuard.requireCustomerAccess(customerId);
        requireOwnedSubscription(customerId, subscriptionId);
        return managementFacade.cancelAtPeriodEnd(subscriptionId, headerIdempotencyKey);
    }

    private SubscriptionSummary requireOwnedSubscription(UUID customerId, UUID subscriptionId) {
        SubscriptionSummary subscription = queryFacade.findSubscriptionById(subscriptionId)
                .orElseThrow(() -> new SubscriptionNotFoundException("Subscription not found"));
        if (!subscription.customerId().equals(customerId)) {
            throw new UnauthorizedCustomerAccessException("Subscription does not belong to customer");
        }
        return subscription;
    }

    private static String resolveIdempotencyKey(String headerKey, String bodyKey) {
        if (headerKey != null && !headerKey.isBlank()) {
            return headerKey.trim();
        }
        if (bodyKey != null && !bodyKey.isBlank()) {
            return bodyKey.trim();
        }
        return null;
    }

    record CreateSubscriptionRequest(
            @NotNull UUID customerId,
            @NotNull UUID planVersionId,
            String currency,
            BillingInterval billingInterval,
            String billingTimezone,
            @Min(0) Integer seatQuantity,
            Instant startAt,
            Instant trialEndAt,
            TrialConversionPolicy trialConversionPolicy,
            String idempotencyKey
    ) {
    }

    record ImmediatePlanChangeRequest(
            @NotNull UUID targetPlanVersionId,
            @Min(0) Integer targetSeatQuantity,
            Instant effectiveAt,
            String idempotencyKey
    ) {
    }

    record SchedulePlanChangeRequest(
            @NotNull UUID targetPlanVersionId,
            @Min(0) Integer targetSeatQuantity,
            Boolean replaceExisting,
            String idempotencyKey
    ) {
    }
}
