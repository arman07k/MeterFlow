package com.meterflow.catalog.api;

/**
 * Mandatory FR2 billable metric aggregation modes.
 */
public enum MetricAggregationType {
    SUM(true, true),
    COUNT(false, false),
    MAXIMUM(true, true),
    UNIQUE_COUNT(true, false),
    LATEST_VALUE(true, true);

    private final boolean requiresTargetProperty;
    private final boolean requiresNumericProperty;

    MetricAggregationType(boolean requiresTargetProperty, boolean requiresNumericProperty) {
        this.requiresTargetProperty = requiresTargetProperty;
        this.requiresNumericProperty = requiresNumericProperty;
    }

    public boolean requiresTargetProperty() {
        return requiresTargetProperty;
    }

    public boolean requiresNumericProperty() {
        return requiresNumericProperty;
    }
}
