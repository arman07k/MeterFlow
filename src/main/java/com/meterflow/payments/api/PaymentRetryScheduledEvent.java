package com.meterflow.payments.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published via Spring Modulith outbox when a dunning retry or final warning is scheduled (FR8).
 */
public record PaymentRetryScheduledEvent(
        UUID dunningCaseId,
        UUID invoiceId,
        UUID customerId,
        UUID subscriptionId,
        DunningCaseStatus dunningStatus,
        int retriesCompleted,
        Instant nextRetryAt,
        Instant suspensionAt,
        Instant scheduledAt
) {
}
