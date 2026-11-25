package com.meterflow.webhooks.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Safe public view of a webhook endpoint. Never includes plaintext or encrypted signing secrets.
 */
public record WebhookEndpointSummary(
        UUID id,
        UUID customerId,
        String name,
        String targetUrl,
        List<String> subscribedEvents,
        String secretPrefix,
        boolean active,
        boolean dualKeyGraceActive,
        Instant previousSecretExpiresAt,
        Instant deletedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
