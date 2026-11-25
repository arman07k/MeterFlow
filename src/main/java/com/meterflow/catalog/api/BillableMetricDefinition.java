package com.meterflow.catalog.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BillableMetricDefinition(
        UUID id,
        String metricKey,
        String name,
        String description,
        MetricAggregationType aggregationType,
        String targetProperty,
        UUID customerId,
        boolean active,
        List<MetricFilterDefinition> filters,
        Instant createdAt,
        Instant updatedAt
) {
    public BillableMetricDefinition {
        filters = filters == null ? List.of() : List.copyOf(filters);
    }
}
