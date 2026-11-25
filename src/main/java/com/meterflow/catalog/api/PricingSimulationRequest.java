package com.meterflow.catalog.api;

import com.meterflow.shared.SupportedCurrency;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PricingSimulationRequest(
        UUID customerId,
        UUID targetPlanVersionId,
        SupportedCurrency currency,
        Instant periodStart,
        Instant periodEnd,
        Integer seatQuantity,
        List<MetricUsageInput> historicalUsageSnapshot,
        List<AllowanceOverride> allowanceOverrides
) {
    public PricingSimulationRequest {
        historicalUsageSnapshot = historicalUsageSnapshot == null ? List.of() : List.copyOf(historicalUsageSnapshot);
        allowanceOverrides = allowanceOverrides == null ? null : List.copyOf(allowanceOverrides);
    }
}
