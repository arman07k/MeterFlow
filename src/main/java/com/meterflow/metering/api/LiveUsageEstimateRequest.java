package com.meterflow.metering.api;

import java.util.List;
import java.util.UUID;

public record LiveUsageEstimateRequest(
        UUID customerId,
        List<UUID> metricIds
) {
    public LiveUsageEstimateRequest {
        metricIds = metricIds == null ? List.of() : List.copyOf(metricIds);
    }
}
