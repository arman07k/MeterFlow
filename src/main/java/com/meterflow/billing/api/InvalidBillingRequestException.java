package com.meterflow.billing.api;

public final class InvalidBillingRequestException extends BillingException {

    public InvalidBillingRequestException(String message) {
        super(message);
    }
}
