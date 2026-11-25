package com.meterflow.webhooks.api;

public enum WebhookDeliveryStatus {
    PENDING,
    RETRYING,
    DELIVERED,
    EXHAUSTED
}
