package com.meterflow.catalog.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Optional caller-supplied remaining free allowance context for rating/simulation.
 */
public record AllowanceOverride(
        UUID metricId,
        UUID targetChargeId,
        BigDecimal remainingFreeQuantity
) {
}
