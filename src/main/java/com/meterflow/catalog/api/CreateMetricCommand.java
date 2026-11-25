package com.meterflow.catalog.api;

import java.util.List;
import java.util.UUID;

public record CreateMetricCommand(
        String metricKey,
        String name,
        String description,
        MetricAggregationType aggregationType,
        String targetProperty,
        UUID customerId,
        List<MetricFilterDefinition> filters
) {
    public CreateMetricCommand {
        filters = filters == null ? List.of() : List.copyOf(filters);
    }
}
