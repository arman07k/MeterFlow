package com.meterflow.payments.api;

import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published via Spring Modulith outbox when a payment is captured (FR8, FR9).
 */
public record PaymentSucceededEvent(
        UUID paymentTransactionId,
        String paymentReference,
        UUID invoiceId,
        UUID customerId,
        UUID subscriptionId,
        SupportedCurrency currency,
        Money amount,
        int attemptNumber,
        boolean recoveredFromDunning,
        Instant capturedAt
) {
}
