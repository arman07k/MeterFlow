package com.meterflow.payments.api;

import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import java.time.Instant;
import java.util.UUID;

public record PaymentTransactionSummary(
        UUID id,
        String paymentReference,
        UUID invoiceId,
        UUID customerId,
        UUID subscriptionId,
        UUID dunningCaseId,
        SupportedCurrency currency,
        Money amount,
        PaymentStatus status,
        PaymentAttemptSource attemptSource,
        int attemptNumber,
        MockPaymentOutcome simulationOutcome,
        boolean retryable,
        String failureCode,
        String failureReason,
        String idempotencyKey,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
