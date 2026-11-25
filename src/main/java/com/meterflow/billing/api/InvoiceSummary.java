package com.meterflow.billing.api;

import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import java.time.Instant;
import java.util.UUID;

public record InvoiceSummary(
        UUID id,
        String invoiceNumber,
        UUID customerId,
        UUID subscriptionId,
        UUID billingRunId,
        Instant periodStart,
        Instant periodEnd,
        String billingTimezone,
        Instant graceExpiresAt,
        SupportedCurrency currency,
        Money subtotalAmount,
        Money taxableAmount,
        Money taxAmount,
        Money totalAmount,
        Money creditsAppliedAmount,
        Money creditNotesAppliedAmount,
        Money amountDue,
        Money payableAmountDue,
        InvoiceStatus status,
        Instant finalizedAt,
        Instant dueAt,
        Instant voidedAt,
        String voidReason,
        Instant createdAt,
        Instant updatedAt
) {
}
