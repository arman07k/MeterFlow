package com.meterflow.portal.internal;

import com.meterflow.catalog.api.AllowanceOverride;
import com.meterflow.catalog.api.MetricUsageInput;
import com.meterflow.catalog.api.PlanRatingResult;
import com.meterflow.catalog.api.PricingSimulationFacade;
import com.meterflow.catalog.api.PricingSimulationRequest;
import com.meterflow.identity.api.CustomerAccessGuard;
import com.meterflow.shared.SupportedCurrency;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PricingSimulationController {

    private final PricingSimulationFacade simulationFacade;
    private final CustomerAccessGuard customerAccessGuard;

    PricingSimulationController(
            PricingSimulationFacade simulationFacade,
            CustomerAccessGuard customerAccessGuard
    ) {
        this.simulationFacade = simulationFacade;
        this.customerAccessGuard = customerAccessGuard;
    }

    @PostMapping("/api/v1/catalog/simulations")
    PlanRatingResult simulateForStaff(@Valid @RequestBody StaffSimulationRequest request) {
        return simulationFacade.simulate(new PricingSimulationRequest(
                request.customerId(),
                request.targetPlanVersionId(),
                request.currency() == null ? null : SupportedCurrency.fromCode(request.currency()),
                request.periodStart(),
                request.periodEnd(),
                request.seatQuantity(),
                request.historicalUsageSnapshot(),
                request.allowanceOverrides()
        ));
    }

    @PostMapping("/api/v1/customer/{customerId}/catalog/simulations")
    PlanRatingResult simulateForCustomer(
            @PathVariable UUID customerId,
            @Valid @RequestBody CustomerSimulationRequest request
    ) {
        customerAccessGuard.requireCustomerAccess(customerId);
        return simulationFacade.simulate(new PricingSimulationRequest(
                customerId,
                request.targetPlanVersionId(),
                request.currency() == null ? null : SupportedCurrency.fromCode(request.currency()),
                request.periodStart(),
                request.periodEnd(),
                request.seatQuantity(),
                request.historicalUsageSnapshot(),
                request.allowanceOverrides()
        ));
    }

    record StaffSimulationRequest(
            UUID customerId,
            @NotNull UUID targetPlanVersionId,
            String currency,
            Instant periodStart,
            Instant periodEnd,
            Integer seatQuantity,
            List<MetricUsageInput> historicalUsageSnapshot,
            List<AllowanceOverride> allowanceOverrides
    ) {
    }

    record CustomerSimulationRequest(
            @NotNull UUID targetPlanVersionId,
            String currency,
            Instant periodStart,
            Instant periodEnd,
            Integer seatQuantity,
            List<MetricUsageInput> historicalUsageSnapshot,
            List<AllowanceOverride> allowanceOverrides
    ) {
    }
}
