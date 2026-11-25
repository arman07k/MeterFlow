package com.meterflow.portal.internal;

import com.meterflow.catalog.api.CatalogResourceNotFoundException;
import com.meterflow.catalog.api.ConfigureAllowanceCommand;
import com.meterflow.catalog.api.ConfigureChargeCommand;
import com.meterflow.catalog.api.ConfigureCommitmentCommand;
import com.meterflow.catalog.api.CreatePlanCommand;
import com.meterflow.catalog.api.PlanCatalogFacade;
import com.meterflow.catalog.api.PlanVersionDetail;
import com.meterflow.catalog.api.PlanVersionStatus;
import com.meterflow.catalog.api.PricingPlanSummary;
import com.meterflow.catalog.api.UpdateDraftPlanVersionCommand;
import com.meterflow.identity.api.CustomerAccessGuard;
import com.meterflow.identity.api.UnauthorizedCustomerAccessException;
import com.meterflow.shared.SupportedCurrency;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CatalogPlanController {

    private static final int MAX_PAGE_SIZE = 100;

    private final PlanCatalogFacade planCatalogFacade;
    private final CustomerAccessGuard customerAccessGuard;

    CatalogPlanController(
            PlanCatalogFacade planCatalogFacade,
            CustomerAccessGuard customerAccessGuard
    ) {
        this.planCatalogFacade = planCatalogFacade;
        this.customerAccessGuard = customerAccessGuard;
    }

    @PostMapping("/api/v1/catalog/plans")
    ResponseEntity<PlanVersionDetail> createPlan(@Valid @RequestBody CreatePlanRequest request) {
        PlanVersionDetail created = planCatalogFacade.createPlanWithDraft(new CreatePlanCommand(
                request.planCode(),
                request.name(),
                request.description(),
                SupportedCurrency.fromCode(request.currency()),
                request.effectiveFrom()
        ));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/api/v1/catalog/plans")
    PageResponse<PricingPlanSummary> listPlans(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(
                Math.max(0, page),
                Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.ASC, "planCode")
        );
        return PageResponse.from(planCatalogFacade.listPlans(pageable));
    }

    @GetMapping("/api/v1/catalog/plans/{planId}")
    PricingPlanSummary getPlan(@PathVariable UUID planId) {
        return planCatalogFacade.findPlanById(planId)
                .orElseThrow(() -> new CatalogResourceNotFoundException("Pricing plan not found"));
    }

    @GetMapping("/api/v1/catalog/plans/{planId}/versions")
    List<PlanVersionDetail> listPlanVersions(@PathVariable UUID planId) {
        return planCatalogFacade.listPlanVersions(planId);
    }

    @PostMapping("/api/v1/catalog/plans/{planId}/versions")
    ResponseEntity<PlanVersionDetail> createDraftVersion(
            @PathVariable UUID planId,
            @RequestBody(required = false) DraftVersionRequest request
    ) {
        Instant effectiveFrom = request == null ? null : request.effectiveFrom();
        PlanVersionDetail draft = planCatalogFacade.createDraftVersion(planId, effectiveFrom);
        return ResponseEntity.status(HttpStatus.CREATED).body(draft);
    }

    @GetMapping("/api/v1/catalog/plan-versions/{planVersionId}")
    PlanVersionDetail getPlanVersion(@PathVariable UUID planVersionId) {
        return planCatalogFacade.findPlanVersionById(planVersionId)
                .orElseThrow(() -> new CatalogResourceNotFoundException("Plan version not found"));
    }

    @PutMapping("/api/v1/catalog/plan-versions/{planVersionId}")
    PlanVersionDetail updateDraftVersion(
            @PathVariable UUID planVersionId,
            @Valid @RequestBody UpdateDraftVersionRequest request
    ) {
        return planCatalogFacade.updateDraftVersion(planVersionId, new UpdateDraftPlanVersionCommand(
                request.effectiveFrom(),
                request.charges(),
                request.freeAllowances(),
                request.minimumCommitments()
        ));
    }

    @PostMapping("/api/v1/catalog/plan-versions/{planVersionId}/publish")
    PlanVersionDetail publishVersion(@PathVariable UUID planVersionId) {
        return planCatalogFacade.publishVersion(planVersionId);
    }

    @PostMapping("/api/v1/catalog/plan-versions/{planVersionId}/clone")
    ResponseEntity<PlanVersionDetail> cloneVersion(
            @PathVariable UUID planVersionId,
            @RequestBody(required = false) DraftVersionRequest request
    ) {
        Instant effectiveFrom = request == null ? null : request.effectiveFrom();
        PlanVersionDetail cloned = planCatalogFacade.cloneVersionToDraft(planVersionId, effectiveFrom);
        return ResponseEntity.status(HttpStatus.CREATED).body(cloned);
    }

    @GetMapping("/api/v1/customer/{customerId}/catalog/plan-versions/{planVersionId}")
    PlanVersionDetail getPublishedPlanVersionForCustomer(
            @PathVariable UUID customerId,
            @PathVariable UUID planVersionId
    ) {
        customerAccessGuard.requireCustomerAccess(customerId);
        PlanVersionDetail version = planCatalogFacade.findPlanVersionById(planVersionId)
                .orElseThrow(() -> new CatalogResourceNotFoundException("Plan version not found"));
        if (version.status() != PlanVersionStatus.PUBLISHED) {
            throw new UnauthorizedCustomerAccessException("Access to unpublished plan version is denied");
        }
        return version;
    }

    record CreatePlanRequest(
            @NotBlank String planCode,
            @NotBlank String name,
            String description,
            @NotBlank String currency,
            Instant effectiveFrom
    ) {
    }

    record DraftVersionRequest(Instant effectiveFrom) {
    }

    record UpdateDraftVersionRequest(
            Instant effectiveFrom,
            @NotNull List<ConfigureChargeCommand> charges,
            List<ConfigureAllowanceCommand> freeAllowances,
            List<ConfigureCommitmentCommand> minimumCommitments
    ) {
    }
}
