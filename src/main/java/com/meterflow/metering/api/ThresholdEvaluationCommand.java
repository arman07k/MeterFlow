package com.meterflow.metering.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ThresholdEvaluationCommand(
        UUID customerId,
        UUID subscriptionId,
        UUID metricId,
        String scopeKey,
        Instant windowStart,
        Instant windowEnd,
        BigDecimal limitValue,
        BigDecimal currentValue
) {
}
