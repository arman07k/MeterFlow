package com.meterflow.payments.api;

public enum PaymentStatus {
    PENDING,
    CAPTURED,
    FAILED;

    public boolean isTerminal() {
        return this == CAPTURED || this == FAILED;
    }
}
