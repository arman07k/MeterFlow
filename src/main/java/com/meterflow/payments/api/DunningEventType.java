package com.meterflow.payments.api;

public enum DunningEventType {
    INITIAL_FAILURE,
    RETRY_SCHEDULED,
    RETRY_ATTEMPTED,
    RETRY_FAILED,
    FINAL_WARNING_ISSUED,
    SUBSCRIPTION_SUSPENDED,
    RECOVERED
}
