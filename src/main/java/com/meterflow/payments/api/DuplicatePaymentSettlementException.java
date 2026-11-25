package com.meterflow.payments.api;

public class DuplicatePaymentSettlementException extends PaymentException {

    public DuplicatePaymentSettlementException(String message) {
        super(message);
    }
}
