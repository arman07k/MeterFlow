package com.meterflow.subscriptions.api;

/**
 * Explicit policy governing what happens when a trial end timestamp is reached (ADR-012).
 * Default is {@link #EXPIRE_ON_END} so expired trials never silently become paid subscriptions.
 */
public enum TrialConversionPolicy {
    EXPIRE_ON_END,
    AUTO_CONVERT_TO_PAID
}
