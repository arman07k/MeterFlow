package com.meterflow.billing.api;

import java.time.Instant;

public record FinalizeInvoiceCommand(
        boolean applyPrepaidCredits,
        Instant dueAt,
        String idempotencyKey
) {
    public FinalizeInvoiceCommand(String idempotencyKey) {
        this(true, null, idempotencyKey);
    }
}
