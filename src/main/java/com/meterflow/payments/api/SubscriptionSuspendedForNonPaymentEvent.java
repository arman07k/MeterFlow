package com.meterflow.payments.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published via Spring Modulith outbox when dunning exhaustion suspends a subscription (FR8).
 */
public record SubscriptionSuspendedForNonPaymentEvent(
        UUID dunningCaseId,
        UUID invoiceId,
        UUID customerId,
        UUID subscriptionId,
        int retriesCompleted,
        String reason,
        Instant suspendedAt
) {
}
