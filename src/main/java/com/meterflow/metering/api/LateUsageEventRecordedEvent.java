package com.meterflow.metering.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published via Spring Modulith outbox when a valid late usage event arrives
 * within the 72-hour post-close window for downstream Billing adjustment processing (FR1, FR6).
 */
public record LateUsageEventRecordedEvent(
        UUID lateRecordId,
        UUID usageEventId,
        UUID customerId,
        UUID subscriptionId,
        UUID metricId,
        Instant eventTimestamp,
        Instant ingestedAt,
        Instant closedPeriodStart,
        Instant closedPeriodEnd,
        BigDecimal hoursLate
) {
}
