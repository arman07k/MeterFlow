package com.meterflow.payments.api;

public class PaymentOverpaymentException extends PaymentException {

    public PaymentOverpaymentException(String message) {
        super(message);
    }
}
