package com.meterflow.metering.api;

import com.meterflow.shared.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LiveUsageEstimateResult(
        UUID customerId,
        UUID subscriptionId,
        UUID planVersionId,
        Instant windowStart,
        Instant windowEnd,
        List<LiveMetricEstimate> metricEstimates,
        Money estimatedTotalAmount,
        boolean cached,
        Instant computedAt,
        long maxFreshnessSeconds
) {
    public LiveUsageEstimateResult {
        metricEstimates = metricEstimates == null ? List.of() : List.copyOf(metricEstimates);
    }
}
