package com.meterflow.billing.api;

import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published via Spring Modulith outbox when an immutable credit note is issued (FR6).
 */
public record CreditNoteIssuedEvent(
        UUID creditNoteId,
        String creditNoteNumber,
        UUID originalInvoiceId,
        UUID customerId,
        SupportedCurrency currency,
        Money totalCredit,
        Money appliedToInvoiceDue,
        Money refundedToCreditWallet,
        Instant issuedAt
) {
}
