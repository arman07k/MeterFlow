package com.meterflow.catalog.api;

import java.util.List;

public record UpdateMetricCommand(
        String name,
        String description,
        String targetProperty,
        Boolean active,
        List<MetricFilterDefinition> filters
) {
    public UpdateMetricCommand {
        filters = filters == null ? null : List.copyOf(filters);
    }
}
