package com.meterflow.webhooks.api;

import java.time.Instant;
import java.util.UUID;

public record WebhookDeliverySummary(
        UUID id,
        UUID endpointId,
        UUID customerId,
        UUID sourceEventId,
        String eventType,
        String eventVersion,
        String idempotencyKey,
        WebhookDeliveryStatus status,
        int attemptCount,
        int maxAttempts,
        Instant nextAttemptAt,
        Instant lastAttemptedAt,
        Instant deliveredAt,
        Integer lastHttpStatus,
        String lastErrorReason,
        Instant createdAt
) {
}
