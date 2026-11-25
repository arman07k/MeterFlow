package com.meterflow.metering.api;

import com.meterflow.catalog.api.MetricFilterDefinition;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UsageQueryRequest(
        UUID customerId,
        UUID metricId,
        Instant windowStart,
        Instant windowEnd,
        List<MetricFilterDefinition> additionalFilters,
        boolean includeTraceableEventIds
) {
    public UsageQueryRequest {
        additionalFilters = additionalFilters == null ? List.of() : List.copyOf(additionalFilters);
    }

    public UsageQueryRequest(UUID customerId, UUID metricId, Instant windowStart, Instant windowEnd) {
        this(customerId, metricId, windowStart, windowEnd, List.of(), false);
    }
}
