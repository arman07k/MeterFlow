package com.meterflow.metering.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record IngestUsageEventCommand(
        UUID eventId,
        UUID customerId,
        UUID metricId,
        UUID subscriptionId,
        BigDecimal quantity,
        Instant eventTimestamp,
        String idempotencyKey,
        Map<String, Object> properties
) {
    public IngestUsageEventCommand {
        if (properties == null) {
            properties = Map.of();
        } else {
            properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        }
    }
}
