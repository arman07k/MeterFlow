package com.meterflow.billing.api;

import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import java.time.Instant;
import java.util.UUID;

public record CreditGrantSummary(
        UUID id,
        UUID customerId,
        SupportedCurrency currency,
        CreditGrantType grantType,
        String description,
        Money originalAmount,
        Money remainingAmount,
        int priority,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
) {
}
