package com.meterflow.billing.api;

import com.meterflow.shared.Money;
import java.time.Instant;
import java.util.UUID;

public record CreditTransactionRecord(
        UUID id,
        UUID creditGrantId,
        UUID customerId,
        UUID invoiceId,
        CreditTransactionType transactionType,
        Money amount,
        Money balanceAfter,
        String reason,
        String idempotencyKey,
        Instant createdAt
) {
}
