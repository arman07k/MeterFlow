package com.meterflow.payments.api;

import java.time.Instant;

public record DunningExecutionSummary(
        Instant evaluatedAt,
        int retriesProcessed,
        int retriesSucceeded,
        int retriesFailed,
        int finalWarningsIssued,
        int subscriptionsSuspended
) {
}
