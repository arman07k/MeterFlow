package com.meterflow.billing.api;

import com.meterflow.shared.SupportedCurrency;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record IssueCreditGrantCommand(
        UUID customerId,
        SupportedCurrency currency,
        CreditGrantType grantType,
        String description,
        BigDecimal amount,
        Integer priority,
        Instant expiresAt,
        String idempotencyKey
) {
}
