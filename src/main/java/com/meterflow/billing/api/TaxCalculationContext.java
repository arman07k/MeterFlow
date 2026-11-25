package com.meterflow.billing.api;

import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import java.util.UUID;

public record TaxCalculationContext(
        UUID customerId,
        SupportedCurrency currency,
        Money taxableAmount,
        TaxPolicySummary policy
) {
}
