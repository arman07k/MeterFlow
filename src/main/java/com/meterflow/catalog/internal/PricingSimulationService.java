package com.meterflow.catalog.internal;

import com.meterflow.catalog.api.CatalogResourceNotFoundException;
import com.meterflow.catalog.api.InvalidRatingContextException;
import com.meterflow.catalog.api.PlanRatingResult;
import com.meterflow.catalog.api.PlanVersionDetail;
import com.meterflow.catalog.api.PlanVersionStatus;
import com.meterflow.catalog.api.PricingSimulationFacade;
import com.meterflow.catalog.api.PricingSimulationRequest;
import com.meterflow.catalog.api.RatingEngineFacade;
import com.meterflow.catalog.api.RatingRequest;
import com.meterflow.identity.api.AuthenticatedActor;
import com.meterflow.identity.api.CustomerAccessGuard;
import com.meterflow.identity.api.IdentityQueryFacade;
import com.meterflow.identity.api.IdentityResourceNotFoundException;
import com.meterflow.identity.api.PlatformRole;
import com.meterflow.identity.api.UnauthorizedCustomerAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Strictly read-only pricing simulation service reusing {@link RatingEngineFacade}
 * with zero persistence side effects (FR10, ADR-001, ADR-011).
 */
@Service
class PricingSimulationService implements PricingSimulationFacade {

    private final PlanVersionLoader planVersionLoader;
    private final RatingEngineFacade ratingEngineFacade;
    private final IdentityQueryFacade identityQueryFacade;
    private final CustomerAccessGuard customerAccessGuard;

    PricingSimulationService(
            PlanVersionLoader planVersionLoader,
            RatingEngineFacade ratingEngineFacade,
            IdentityQueryFacade identityQueryFacade,
            CustomerAccessGuard customerAccessGuard
    ) {
        this.planVersionLoader = planVersionLoader;
        this.ratingEngineFacade = ratingEngineFacade;
        this.identityQueryFacade = identityQueryFacade;
        this.customerAccessGuard = customerAccessGuard;
    }

    @Override
    @Transactional(readOnly = true)
    public PlanRatingResult simulate(PricingSimulationRequest request) {
        if (request == null || request.targetPlanVersionId() == null) {
            throw new InvalidRatingContextException("PricingSimulationRequest and targetPlanVersionId must not be null");
        }

        PlanVersionDetail targetVersion = planVersionLoader
                .loadAuthoritativePlanVersion(request.targetPlanVersionId())
                .orElseThrow(() -> new CatalogResourceNotFoundException("Target plan version not found"));

        enforceSimulationAuthorization(request, targetVersion);

        if (request.customerId() != null && identityQueryFacade.findCustomerById(request.customerId()).isEmpty()) {
            throw new IdentityResourceNotFoundException("Customer not found for pricing simulation");
        }

        RatingRequest ratingRequest = new RatingRequest(
                request.customerId(),
                request.targetPlanVersionId(),
                request.currency() != null ? request.currency() : targetVersion.currency(),
                request.periodStart(),
                request.periodEnd(),
                request.seatQuantity(),
                request.historicalUsageSnapshot(),
                request.allowanceOverrides()
        );
        return ratingEngineFacade.rate(ratingRequest);
    }

    private void enforceSimulationAuthorization(
            PricingSimulationRequest request,
            PlanVersionDetail targetVersion
    ) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return;
        }
        AuthenticatedActor actor = customerAccessGuard.requireAuthenticatedActor();
        if (actor.role() == PlatformRole.CUSTOMER) {
            if (targetVersion.status() != PlanVersionStatus.PUBLISHED) {
                throw new UnauthorizedCustomerAccessException(
                        "Customers may only simulate against PUBLISHED plan versions"
                );
            }
            if (request.customerId() == null) {
                throw new UnauthorizedCustomerAccessException("Customer identity is required for customer simulation");
            }
            customerAccessGuard.requireCustomerAccess(request.customerId());
        }
    }
}
