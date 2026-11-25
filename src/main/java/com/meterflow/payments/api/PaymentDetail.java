package com.meterflow.payments.api;

import java.util.List;

public record PaymentDetail(
        PaymentTransactionSummary payment,
        List<PaymentAttemptRecord> attempts
) {
    public PaymentDetail {
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
    }
}
