package com.meterflow.catalog.api;

import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PlanRatingResult(
        UUID customerId,
        UUID planVersionId,
        String planCode,
        int versionNumber,
        SupportedCurrency currency,
        Instant periodStart,
        Instant periodEnd,
        List<RatedChargeBreakdown> chargeBreakdowns,
        Money calculatedUsageAmount,
        Money planMinimumCommitment,
        Money planMinimumTrueUp,
        Money finalPersistedAmount,
        Money finalPayableAmount
) {
    public PlanRatingResult {
        chargeBreakdowns = chargeBreakdowns == null ? List.of() : List.copyOf(chargeBreakdowns);
    }
}
