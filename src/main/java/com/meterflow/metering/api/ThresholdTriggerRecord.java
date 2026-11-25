package com.meterflow.metering.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ThresholdTriggerRecord(
        UUID id,
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
