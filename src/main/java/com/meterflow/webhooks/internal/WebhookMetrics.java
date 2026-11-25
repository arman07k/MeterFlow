package com.meterflow.webhooks.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality Micrometer operational metrics for outbound webhook delivery (ADR-009).
 */
@Component
class WebhookMetrics {

    private final MeterRegistry meterRegistry;

    WebhookMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    void recordEnqueued(String eventType) {
        Counter.builder("meterflow.webhooks.enqueued")
                .tag("eventType", safeTag(eventType))
                .register(meterRegistry)
                .increment();
    }

    void recordDeliveryOutcome(String eventType, String outcome) {
        Counter.builder("meterflow.webhooks.deliveries")
                .tag("eventType", safeTag(eventType))
                .tag("outcome", safeTag(outcome))
                .register(meterRegistry)
                .increment();
    }

    void recordManualReplay(String outcome) {
        Counter.builder("meterflow.webhooks.replays")
                .tag("outcome", safeTag(outcome))
                .register(meterRegistry)
                .increment();
    }

    private static String safeTag(String value) {
        return (value == null || value.isBlank()) ? "unknown" : value;
    }
}
