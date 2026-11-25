package com.meterflow.catalog.api;

import java.util.List;

public record MetricFilterDefinition(
        int filterOrder,
        String propertyKey,
        MetricFilterOperator operator,
        List<String> values
) {
    public MetricFilterDefinition {
        values = values == null ? List.of() : List.copyOf(values);
    }
}
