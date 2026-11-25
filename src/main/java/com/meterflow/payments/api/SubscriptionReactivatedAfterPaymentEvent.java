package com.meterflow.payments.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published via Spring Modulith outbox when successful payment reactivates a
 * PAST_DUE or SUSPENDED subscription back to ACTIVE (FR8).
 */
public record SubscriptionReactivatedAfterPaymentEvent(
        UUID paymentTransactionId,
        UUID dunningCaseId,
        UUID invoiceId,
        UUID customerId,
        UUID subscriptionId,
        String previousStatus,
        Instant reactivatedAt
) {
}
