package com.meterflow.payments.api;

import java.time.Instant;
import java.util.UUID;

public record DunningCaseSummary(
        UUID id,
        UUID invoiceId,
        UUID customerId,
        UUID subscriptionId,
        UUID dunningPolicyId,
        DunningCaseStatus status,
        Instant initialFailureAt,
        int retriesCompleted,
        Instant nextRetryAt,
        Instant suspensionAt,
        Instant suspendedAt,
        Instant recoveredAt,
        String lastFailureCode,
        String lastFailureReason,
        Instant createdAt,
        Instant updatedAt
) {
}
