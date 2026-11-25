package com.meterflow.webhooks.api;

import java.util.Set;
import java.util.UUID;

public record RegisterWebhookEndpointCommand(
        UUID customerId,
        String name,
        String targetUrl,
        Set<String> subscribedEvents
) {
}
