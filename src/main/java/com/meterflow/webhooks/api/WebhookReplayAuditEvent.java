package com.meterflow.webhooks.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event emitted whenever an authorized user or operator manually replays a webhook delivery.
 */
public record WebhookReplayAuditEvent(
        UUID deliveryId,
        UUID endpointId,
        UUID customerId,
        String eventType,
        UUID actorUserId,
        String actorEmail,
        Integer httpStatus,
        WebhookDeliveryStatus resultingStatus,
        Instant replayedAt
) {
}
