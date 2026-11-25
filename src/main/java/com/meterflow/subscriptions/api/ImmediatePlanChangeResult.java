package com.meterflow.subscriptions.api;

public record ImmediatePlanChangeResult(
        SubscriptionSummary subscription,
        ProrationRecord prorationRecord
) {
}
