package com.meterflow.catalog.api;

import java.math.BigDecimal;
import java.util.UUID;

public record ConfigureAllowanceCommand(
        UUID metricId,
        String targetChargeCode,
        BigDecimal freeQuantity,
        AllowanceResetPeriod resetPeriod
) {
}
