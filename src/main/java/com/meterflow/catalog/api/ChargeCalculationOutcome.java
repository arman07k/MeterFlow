package com.meterflow.catalog.api;

import com.meterflow.shared.Money;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ChargeCalculationOutcome(
        Money rawAmount,
        BigDecimal effectiveUnitPrice,
        List<AppliedTierBreakdown> appliedTiers,
        Map<String, Object> metadata
) {
    public ChargeCalculationOutcome {
        appliedTiers = appliedTiers == null ? List.of() : List.copyOf(appliedTiers);
        metadata = metadata == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
