package com.meterflow.payments.api;

import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import java.time.Instant;
import java.util.UUID;

public record PaymentAttemptRecord(
        UUID id,
        UUID paymentTransactionId,
        UUID invoiceId,
        UUID customerId,
        UUID dunningCaseId,
        int attemptNumber,
        PaymentAttemptSource attemptSource,
        MockPaymentOutcome simulationOutcome,
        PaymentAttemptStatus status,
        boolean retryable,
        String failureCode,
        String failureReason,
        Money amount,
        SupportedCurrency currency,
        String idempotencyKey,
        Instant attemptedAt
) {
}
