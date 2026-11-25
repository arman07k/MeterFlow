package com.meterflow.billing.api;

import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published via Spring Modulith outbox when an invoice is locked as FINALIZED
 * (for future Payments, Notifications, and outbound invoice.finalised Webhooks — FR5, FR8, FR9).
 */
public record InvoiceFinalizedEvent(
        UUID invoiceId,
        String invoiceNumber,
        UUID customerId,
        UUID subscriptionId,
        SupportedCurrency currency,
        Money totalAmount,
        Money creditsAppliedAmount,
        Money payableAmountDue,
        Instant periodStart,
        Instant periodEnd,
        Instant finalizedAt,
        Instant dueAt
) {
}
