package com.meterflow.billing.api;

import java.math.BigDecimal;
import java.util.UUID;

public record ConfigureTaxPolicyCommand(
        String policyCode,
        String name,
        UUID customerId,
        String jurisdiction,
        String taxName,
        BigDecimal ratePercent,
        boolean taxExempt
) {
}
