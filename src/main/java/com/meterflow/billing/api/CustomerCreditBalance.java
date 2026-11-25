package com.meterflow.billing.api;

import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import java.time.Instant;
import java.util.UUID;

public record CustomerCreditBalance(
        UUID customerId,
        SupportedCurrency currency,
        Money availableBalance,
        int activeGrantCount,
        Instant computedAt
) {
}
