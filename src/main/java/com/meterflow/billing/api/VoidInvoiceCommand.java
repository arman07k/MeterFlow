package com.meterflow.billing.api;

public record VoidInvoiceCommand(
        String reason,
        String idempotencyKey
) {
}
