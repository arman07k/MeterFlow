package com.meterflow.metering.api;

import com.meterflow.catalog.api.MetricAggregationType;
import java.math.BigDecimal;
import java.util.UUID;

public record LiveMetricEstimate(
        UUID metricId,
        String metricKey,
        MetricAggregationType aggregationType,
        BigDecimal aggregatedQuantity,
        long matchedEventCount,
        long lateEventCount
) {
}
