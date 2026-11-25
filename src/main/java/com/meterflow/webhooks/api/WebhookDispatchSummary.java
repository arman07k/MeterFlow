package com.meterflow.webhooks.api;

public record WebhookDispatchSummary(
        int claimedCount,
        int deliveredCount,
        int scheduledRetryCount,
        int exhaustedCount
) {
}
