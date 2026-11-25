package com.meterflow.billing.api;

import java.util.List;

public record InvoiceDetail(
        InvoiceSummary invoice,
        List<InvoiceLineRecord> lines,
        List<InvoiceTaxLineRecord> taxLines
) {
    public InvoiceDetail {
        lines = lines == null ? List.of() : List.copyOf(lines);
        taxLines = taxLines == null ? List.of() : List.copyOf(taxLines);
    }
}
