package com.meterflow.catalog.api;

import com.meterflow.shared.Money;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RatedChargeBreakdown(
        UUID planVersionId,
        UUID chargeId,
        String chargeCode,
        String chargeType,
        UUID metricId,
        BigDecimal originalQuantity,
        BigDecimal freeAllowanceQuantity,
        BigDecimal billableQuantity,
        BigDecimal effectiveUnitPrice,
        List<AppliedTierBreakdown> appliedTiers,
        Money calculatedAmount,
        Money chargeMinimumCommitment,
        Money chargeMinimumTrueUp,
        Money finalPersistedAmount,
        Map<String, Object> metadata
) {
    public RatedChargeBreakdown {
        appliedTiers = appliedTiers == null ? List.of() : List.copyOf(appliedTiers);
        metadata = metadata == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
