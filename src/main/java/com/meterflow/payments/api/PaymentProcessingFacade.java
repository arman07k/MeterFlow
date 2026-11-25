package com.meterflow.payments.api;

public interface PaymentProcessingFacade {

    PaymentDetail initiatePayment(InitiatePaymentCommand command);
}
