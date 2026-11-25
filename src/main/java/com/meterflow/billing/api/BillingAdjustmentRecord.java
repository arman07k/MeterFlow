package com.meterflow.billing.api;

import com.meterflow.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record BillingAdjustmentRecord(
        UUID id,
        UUID customerId,
        UUID subscriptionId,
        UUID originalInvoiceId,
        UUID appliedInvoiceId,
        UUID lateUsageRecordId,
        UUID metricId,
        BillingAdjustmentType adjustmentType,
        String description,
        BigDecimal quantityDelta,
        Money amount,
        BillingAdjustmentStatus status,
        Map<String, Object> calculationBreakdown,
        Instant createdAt,
        Instant updatedAt
) {
    public BillingAdjustmentRecord {
        calculationBreakdown = calculationBreakdown == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(calculationBreakdown));
    }
}
