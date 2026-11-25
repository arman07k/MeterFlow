package com.meterflow.payments.api;

import com.meterflow.shared.SupportedCurrency;
import java.math.BigDecimal;
import java.util.UUID;

public record InitiatePaymentCommand(
        UUID invoiceId,
        BigDecimal amount,
        SupportedCurrency currency,
        MockPaymentOutcome simulationOutcome,
        PaymentAttemptSource attemptSource,
        String idempotencyKey
) {
    public InitiatePaymentCommand(
            UUID invoiceId,
            BigDecimal amount,
            SupportedCurrency currency,
            MockPaymentOutcome simulationOutcome,
            String idempotencyKey
    ) {
        this(invoiceId, amount, currency, simulationOutcome, PaymentAttemptSource.MANUAL, idempotencyKey);
    }
}
