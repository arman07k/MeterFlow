package com.meterflow.billing.api;

public final class InsufficientCreditBalanceException extends BillingException {

    public InsufficientCreditBalanceException(String message) {
        super(message);
    }
}
