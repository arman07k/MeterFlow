package com.meterflow.webhooks.api;

import java.util.Set;

public record UpdateWebhookEndpointCommand(
        String name,
        String targetUrl,
        Set<String> subscribedEvents,
        Boolean active
) {
}
