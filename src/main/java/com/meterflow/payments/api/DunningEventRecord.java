package com.meterflow.payments.api;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record DunningEventRecord(
        UUID id,
        UUID dunningCaseId,
        UUID invoiceId,
        UUID customerId,
        UUID subscriptionId,
        DunningEventType eventType,
        String fromStatus,
        String toStatus,
        Integer attemptNumber,
        Instant scheduledFor,
        Map<String, Object> details,
        Instant occurredAt
) {
    public DunningEventRecord {
        details = details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }
}
