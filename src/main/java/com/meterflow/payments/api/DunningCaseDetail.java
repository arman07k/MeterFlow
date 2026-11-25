package com.meterflow.payments.api;

import java.util.List;

public record DunningCaseDetail(
        DunningCaseSummary dunningCase,
        List<DunningEventRecord> events,
        List<PaymentAttemptRecord> paymentAttempts
) {
    public DunningCaseDetail {
        events = events == null ? List.of() : List.copyOf(events);
        paymentAttempts = paymentAttempts == null ? List.of() : List.copyOf(paymentAttempts);
    }
}
