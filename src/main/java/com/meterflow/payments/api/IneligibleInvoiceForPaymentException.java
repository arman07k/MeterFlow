package com.meterflow.payments.api;

public class IneligibleInvoiceForPaymentException extends PaymentException {

    public IneligibleInvoiceForPaymentException(String message) {
        super(message);
    }
}
