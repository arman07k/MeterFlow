package com.meterflow.catalog.api;

import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import java.util.List;
import java.util.UUID;

/**
 * Canonical breakdown of proratable recurring charges vs. usage-based charges excluded from
 * fixed-fee proration (ADR-012).
 */
public record RecurringPlanQuote(
        UUID planVersionId,
        String planCode,
        int versionNumber,
        SupportedCurrency currency,
        Money recurringTotalAmount,
        List<RatedChargeBreakdown> recurringChargeBreakdowns,
        List<String> nonProratableUsageChargeCodes
) {
    public RecurringPlanQuote {
        recurringChargeBreakdowns = recurringChargeBreakdowns == null ? List.of() : List.copyOf(recurringChargeBreakdowns);
        nonProratableUsageChargeCodes = nonProratableUsageChargeCodes == null ? List.of() : List.copyOf(nonProratableUsageChargeCodes);
    }
}
