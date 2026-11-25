package com.meterflow.webhooks.api;

import java.util.Set;

/**
 * Canonical outbound webhook event names supported by MeterFlow (FR9, ADR-009).
 */
public final class WebhookEventTypes {

    public static final String INVOICE_FINALISED = "invoice.finalised";
    public static final String PAYMENT_FAILED = "payment.failed";
    public static final String USAGE_THRESHOLD_REACHED = "usage.threshold_reached";
    public static final String PAYMENT_SUCCEEDED = "payment.succeeded";
    public static final String CREDIT_NOTE_ISSUED = "credit_note.issued";
    public static final String SUBSCRIPTION_SUSPENDED = "subscription.suspended";
    public static final String SUBSCRIPTION_REACTIVATED = "subscription.reactivated";

    public static final Set<String> SUPPORTED = Set.of(
            INVOICE_FINALISED,
            PAYMENT_FAILED,
            USAGE_THRESHOLD_REACHED,
            PAYMENT_SUCCEEDED,
            CREDIT_NOTE_ISSUED,
            SUBSCRIPTION_SUSPENDED,
            SUBSCRIPTION_REACTIVATED
    );

    private WebhookEventTypes() {
    }

    public static boolean isSupported(String eventType) {
        return eventType != null && SUPPORTED.contains(eventType.trim());
    }
}
