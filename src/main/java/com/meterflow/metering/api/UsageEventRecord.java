package com.meterflow.metering.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record UsageEventRecord(
        UUID id,
        UUID customerId,
        UUID subscriptionId,
        UUID metricId,
        String idempotencyKey,
        BigDecimal quantity,
        BigDecimal measuredValue,
        String distinctKey,
        Instant eventTimestamp,
        Instant ingestedAt,
        Map<String, Object> properties,
        boolean filterMatched,
        boolean lateEvent,
        LateClassification lateClassification,
        Instant billingPeriodStart,
        Instant billingPeriodEnd,
        EventProcessingStatus processingStatus
) {
    public UsageEventRecord {
        properties = properties == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }
}
