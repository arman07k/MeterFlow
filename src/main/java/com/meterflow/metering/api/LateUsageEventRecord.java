package com.meterflow.metering.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LateUsageEventRecord(
        UUID id,
        UUID usageEventId,
        UUID customerId,
        UUID subscriptionId,
        UUID metricId,
        Instant eventTimestamp,
        Instant ingestedAt,
        Instant closedPeriodStart,
        Instant closedPeriodEnd,
        BigDecimal hoursLate,
        LateAdjustmentStatus adjustmentStatus,
        Instant createdAt,
        Instant consumedAt
) {
}
