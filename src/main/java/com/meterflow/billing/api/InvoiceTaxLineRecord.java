package com.meterflow.billing.api;

import com.meterflow.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record InvoiceTaxLineRecord(
        UUID id,
        UUID invoiceId,
        UUID taxPolicyId,
        String taxName,
        String jurisdiction,
        Money taxableAmount,
        BigDecimal ratePercent,
        Money taxAmount,
        Instant createdAt
) {
}
