package com.meterflow.billing.api;

import com.meterflow.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record InvoiceLineRecord(
        UUID id,
        UUID invoiceId,
        int lineOrder,
        InvoiceLineType lineType,
        String description,
        UUID subscriptionId,
        UUID planVersionId,
        UUID chargeId,
        String chargeCode,
        String chargeType,
        UUID metricId,
        Instant windowStart,
        Instant windowEnd,
        Integer seatQuantity,
        BigDecimal rawQuantity,
        BigDecimal freeAllowanceQuantity,
        BigDecimal billableQuantity,
        BigDecimal unitPrice,
        Money lineAmount,
        boolean taxable,
        UUID sourceReferenceId,
        Map<String, Object> calculationBreakdown,
        Instant createdAt
) {
    public InvoiceLineRecord {
        calculationBreakdown = calculationBreakdown == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(calculationBreakdown));
    }
}
