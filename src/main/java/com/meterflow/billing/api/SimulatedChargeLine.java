package com.meterflow.billing.api;

import com.meterflow.catalog.api.AppliedTierBreakdown;
import com.meterflow.shared.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record SimulatedChargeLine(
        UUID chargeId,
        String chargeCode,
        String chargeType,
        String description,
        UUID metricId,
        BigDecimal rawQuantity,
        BigDecimal freeAllowanceQuantity,
        BigDecimal billableQuantity,
        BigDecimal effectiveUnitPrice,
        List<AppliedTierBreakdown> appliedTiers,
        Money chargeMinimumTrueUp,
        Money amount,
        long sourceEventCount,
        String dataNote
) {
    public SimulatedChargeLine {
        appliedTiers = appliedTiers == null ? List.of() : List.copyOf(appliedTiers);
    }
}
