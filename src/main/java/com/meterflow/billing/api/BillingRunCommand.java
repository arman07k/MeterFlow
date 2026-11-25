package com.meterflow.billing.api;

import java.time.Instant;
import java.util.UUID;

public record BillingRunCommand(
        UUID customerId,
        Instant cutoffTimestamp,
        Integer gracePeriodHours,
        String idempotencyKey
) {
}
