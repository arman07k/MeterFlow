package com.meterflow.catalog.api;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Lightweight immutable event projection for pure FR2 metric filter/property evaluation.
 * Raw event ingestion and persistence belong strictly to the future metering module.
 */
public record MetricEventSample(
        UUID eventId,
        Instant eventTimestamp,
        Instant ingestedAt,
        Map<String, Object> properties
) {
    public MetricEventSample {
        if (properties == null) {
            properties = Map.of();
        } else {
            properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        }
    }
}
