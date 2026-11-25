package com.meterflow.billing.api;

import com.meterflow.shared.Money;
import java.math.BigDecimal;
import java.util.UUID;

public record CalculatedTaxLine(
        UUID taxPolicyId,
        String taxName,
        String jurisdiction,
        Money taxableAmount,
        BigDecimal ratePercent,
        Money taxAmount
) {
}
