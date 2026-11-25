package com.meterflow.billing.api;

import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CreditNoteDetail(
        UUID id,
        String creditNoteNumber,
        UUID originalInvoiceId,
        UUID customerId,
        SupportedCurrency currency,
        CreditNoteType creditType,
        String reason,
        Money subtotalCredit,
        Money taxCredit,
        Money totalCredit,
        Money appliedToInvoiceDue,
        Money refundedToCreditWallet,
        Instant issuedAt,
        List<CreditNoteLineRecord> lines
) {
    public CreditNoteDetail {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
