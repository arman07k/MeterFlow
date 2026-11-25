package com.meterflow.payments.api;

import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published via Spring Modulith outbox on every payment failure
 * (maps cleanly to future FR9 outbound {@code payment.failed} webhooks).
 */
public record PaymentFailedEvent(
        UUID paymentTransactionId,
        String paymentReference,
        UUID invoiceId,
        UUID customerId,
        UUID subscriptionId,
        UUID dunningCaseId,
        SupportedCurrency currency,
        Money amount,
        int attemptNumber,
        boolean retryable,
        String failureCode,
        String failureReason,
        DunningCaseStatus dunningStatus,
        Instant nextRetryAt,
        Instant suspensionAt,
        Instant failedAt
) {
}
