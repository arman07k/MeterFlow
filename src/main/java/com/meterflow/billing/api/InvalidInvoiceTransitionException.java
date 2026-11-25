package com.meterflow.billing.api;

public final class InvalidInvoiceTransitionException extends BillingException {

    public InvalidInvoiceTransitionException(String message) {
        super(message);
    }
}
