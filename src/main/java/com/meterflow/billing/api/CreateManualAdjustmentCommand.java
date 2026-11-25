package com.meterflow.billing.api;

import com.meterflow.shared.SupportedCurrency;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateManualAdjustmentCommand(
        UUID customerId,
        UUID subscriptionId,
        UUID originalInvoiceId,
        BillingAdjustmentType adjustmentType,
        String description,
        BigDecimal amount,
        SupportedCurrency currency,
        String idempotencyKey
) {
}
