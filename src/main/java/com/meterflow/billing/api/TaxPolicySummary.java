package com.meterflow.billing.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TaxPolicySummary(
        UUID id,
        String policyCode,
        String name,
        UUID customerId,
        String jurisdiction,
        String taxName,
        BigDecimal ratePercent,
        boolean taxExempt,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
}
