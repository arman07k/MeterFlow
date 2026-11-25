package com.meterflow.catalog.api;

import java.math.BigDecimal;
import java.util.UUID;

public record FreeAllowanceDefinition(
        UUID id,
        UUID metricId,
        UUID targetChargeId,
        BigDecimal freeQuantity,
        AllowanceResetPeriod resetPeriod
) {
}
