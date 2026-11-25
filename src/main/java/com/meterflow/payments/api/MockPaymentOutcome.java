package com.meterflow.payments.api;

/**
 * Deterministic mock payment processor outcomes (FR8, ADR-007, ADR-015).
 * Strictly internal simulation — zero external payment gateways.
 */
public enum MockPaymentOutcome {

    SIMULATE_SUCCESS(true, false, null, null),
    SIMULATE_SOFT_DECLINE(false, true, "SOFT_DECLINE", "Simulated temporary card/issuer decline"),
    SIMULATE_HARD_DECLINE(false, false, "HARD_DECLINE", "Simulated permanent payment method rejection"),
    SIMULATE_TIMEOUT(false, true, "GATEWAY_TIMEOUT", "Simulated transient processor timeout");

    private final boolean success;
    private final boolean retryable;
    private final String failureCode;
    private final String failureReason;

    MockPaymentOutcome(boolean success, boolean retryable, String failureCode, String failureReason) {
        this.success = success;
        this.retryable = retryable;
        this.failureCode = failureCode;
        this.failureReason = failureReason;
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public String failureCode() {
        return failureCode;
    }

    public String failureReason() {
        return failureReason;
    }
}
