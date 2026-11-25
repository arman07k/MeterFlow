package com.meterflow.billing.api;

import com.meterflow.shared.SupportedCurrency;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record IssueCreditNoteCommand(
        UUID originalInvoiceId,
        SupportedCurrency currency,
        CreditNoteType creditType,
        String reason,
        BigDecimal subtotalCreditOverride,
        List<CreditNoteLineInput> lines,
        String idempotencyKey
) {
    public IssueCreditNoteCommand {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
