package com.meterflow.billing.api;

import com.meterflow.shared.Money;
import java.time.Instant;
import java.util.UUID;

public record CreditNoteLineRecord(
        UUID id,
        UUID creditNoteId,
        UUID originalInvoiceLineId,
        String description,
        Money creditAmount,
        Money taxAmount,
        Instant createdAt
) {
}
