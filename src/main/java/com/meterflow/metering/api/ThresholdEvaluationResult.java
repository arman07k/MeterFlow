package com.meterflow.metering.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ThresholdEvaluationResult(
        UUID customerId,
        String scopeKey,
        BigDecimal currentValue,
        BigDecimal limitValue,
        BigDecimal utilizationPercent,
        List<ThresholdTriggerRecord> newlyTriggered,
        List<Integer> alreadyTriggeredPercents
) {
    public ThresholdEvaluationResult {
        newlyTriggered = newlyTriggered == null ? List.of() : List.copyOf(newlyTriggered);
        alreadyTriggeredPercents = alreadyTriggeredPercents == null ? List.of() : List.copyOf(alreadyTriggeredPercents);
    }
}
