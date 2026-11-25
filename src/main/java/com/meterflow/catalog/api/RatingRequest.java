package com.meterflow.catalog.api;

import com.meterflow.shared.SupportedCurrency;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RatingRequest(
        UUID customerId,
        UUID planVersionId,
        SupportedCurrency currency,
        Instant periodStart,
        Instant periodEnd,
        Integer seatQuantity,
        List<MetricUsageInput> metricUsages,
        List<AllowanceOverride> allowanceOverrides
) {
    public RatingRequest {
        metricUsages = metricUsages == null ? List.of() : List.copyOf(metricUsages);
        allowanceOverrides = allowanceOverrides == null ? null : List.copyOf(allowanceOverrides);
    }
}
