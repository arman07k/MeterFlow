package com.meterflow.subscriptions.api;

import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProrationRecord(
        UUID id,
        UUID subscriptionId,
        UUID previousPlanVersionId,
        UUID newPlanVersionId,
        int previousSeatQuantity,
        int newSeatQuantity,
        Instant periodStart,
        Instant periodEnd,
        Instant effectiveAt,
        long totalPeriodSeconds,
        long remainingSeconds,
        BigDecimal prorationFactor,
        Money previousPlanRecurringAmount,
        Money newPlanRecurringAmount,
        Money creditAmount,
        Money debitAmount,
        Money netProrationAmount,
        SupportedCurrency currency,
        List<String> nonProratableUsageCharges,
        String calculationPolicy,
        String idempotencyKey,
        Instant createdAt
) {
    public ProrationRecord {
        nonProratableUsageCharges = nonProratableUsageCharges == null ? List.of() : List.copyOf(nonProratableUsageCharges);
    }
}
