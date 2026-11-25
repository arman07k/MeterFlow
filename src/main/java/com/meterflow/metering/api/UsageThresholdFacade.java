package com.meterflow.metering.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface UsageThresholdFacade {

    ThresholdEvaluationResult evaluateThresholds(ThresholdEvaluationCommand command);

    List<ThresholdTriggerRecord> listTriggeredThresholds(UUID customerId, Instant windowStart, Instant windowEnd);
}
