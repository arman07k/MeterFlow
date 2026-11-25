package com.meterflow.payments.api;

public class InvalidPaymentRequestException extends PaymentException {

    public InvalidPaymentRequestException(String message) {
        super(message);
    }
}
