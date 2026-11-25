package com.meterflow.subscriptions.api;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record SubscriptionTransitionRecord(
        UUID id,
        UUID subscriptionId,
        SubscriptionStatus fromStatus,
        SubscriptionStatus toStatus,
        String reason,
        Instant effectiveAt,
        String idempotencyKey,
        Map<String, Object> metadata,
        Instant createdAt
) {
    public SubscriptionTransitionRecord {
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
