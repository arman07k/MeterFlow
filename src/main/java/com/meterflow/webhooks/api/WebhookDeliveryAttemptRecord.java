package com.meterflow.webhooks.api;

import java.time.Instant;
import java.util.UUID;

public record WebhookDeliveryAttemptRecord(
        UUID id,
        UUID deliveryId,
        UUID endpointId,
        UUID customerId,
        int attemptNumber,
        boolean manualReplay,
        UUID replayedByUserId,
        long signatureTimestamp,
        Integer httpStatus,
        int durationMs,
        String responseExcerpt,
        String errorReason,
        Instant attemptedAt
) {
}
