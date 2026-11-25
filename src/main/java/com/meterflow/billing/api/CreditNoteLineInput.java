package com.meterflow.billing.api;

import java.math.BigDecimal;
import java.util.UUID;

public record CreditNoteLineInput(
        UUID originalInvoiceLineId,
        String description,
        BigDecimal creditAmount
) {
}
