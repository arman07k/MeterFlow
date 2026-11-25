package com.meterflow.metering.api;

import com.meterflow.catalog.api.MetricAggregationType;
import com.meterflow.catalog.api.MetricFilterDefinition;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UsageAggregationResult(
        UUID customerId,
        UUID metricId,
        String metricKey,
        MetricAggregationType aggregationType,
        Instant windowStart,
        Instant windowEnd,
        BigDecimal aggregatedQuantity,
        long matchedEventCount,
        long lateEventCount,
        List<MetricFilterDefinition> appliedFilters,
        UsageDataSource dataSource,
        Instant computedAt,
        long freshnessSeconds,
        List<UUID> traceableEventIds
) {
    public UsageAggregationResult {
        appliedFilters = appliedFilters == null ? List.of() : List.copyOf(appliedFilters);
        traceableEventIds = traceableEventIds == null ? List.of() : List.copyOf(traceableEventIds);
    }
}
