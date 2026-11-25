package com.meterflow.webhooks.api;

import java.time.Instant;

/**
 * One-time signing secret disclosure returned strictly upon endpoint registration or rotation.
 * Callers must display or store this secret immediately; MeterFlow never redisplays it.
 */
public record WebhookSecretDisclosure(
        WebhookEndpointSummary endpoint,
        String signingSecret,
        Instant previousSecretExpiresAt
) {
}
