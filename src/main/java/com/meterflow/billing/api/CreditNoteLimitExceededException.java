package com.meterflow.billing.api;

public final class CreditNoteLimitExceededException extends BillingException {

    public CreditNoteLimitExceededException(String message) {
        super(message);
    }
}
