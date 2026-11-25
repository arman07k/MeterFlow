package com.meterflow.metering.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Deduplicated domain event published via Spring Modulith outbox when usage or spend crosses
 * 50%, 80%, or 100% of a configured limit for future Notifications and Webhooks consumption (FR7).
 */
public record UsageThresholdReachedEvent(
        UUID evaluationId,
        UUID customerId,
        UUID subscriptionId,
        UUID metricId,
        String scopeKey,
        Instant windowStart,
        Instant windowEnd,
        int thresholdPercent,
        BigDecimal limitValue,
        BigDecimal currentValue,
        BigDecimal utilizationPercent,
        Instant triggeredAt
) {
}
