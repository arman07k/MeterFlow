package com.meterflow.metering.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RollupReconciliationResult(
        UUID customerId,
        UUID metricId,
        Instant windowStart,
        Instant windowEnd,
        int bucketsReconciled,
        long eventsProcessed,
        BigDecimal reconciledSum,
        long reconciledEventCount,
        Instant reconciledAt
) {
}
