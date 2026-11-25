package com.meterflow.billing.api;

import java.time.Instant;
import java.util.UUID;

public record GenerateDraftInvoiceCommand(
        UUID subscriptionId,
        Instant periodStart,
        Instant periodEnd,
        Integer gracePeriodHours,
        UUID billingRunId,
        String idempotencyKey
) {
    public GenerateDraftInvoiceCommand(UUID subscriptionId, String idempotencyKey) {
        this(subscriptionId, null, null, null, null, idempotencyKey);
    }
}
