package com.meterflow.webhooks.api;

import java.util.List;

public record WebhookDeliveryDetail(
        WebhookDeliverySummary summary,
        String payloadJson,
        List<WebhookDeliveryAttemptRecord> attempts
) {
}
