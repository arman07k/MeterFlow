package com.meterflow.catalog.api;

import java.math.BigDecimal;
import java.util.UUID;

public record MetricUsageInput(
        UUID metricId,
        BigDecimal aggregatedQuantity
) {
}
