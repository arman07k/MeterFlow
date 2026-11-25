package com.meterflow.billing.api;

import java.time.Instant;
import java.util.UUID;

public record BillingRunSummary(
        UUID id,
        Long batchJobExecutionId,
        BillingRunType runType,
        UUID customerId,
        Instant cutoffTimestamp,
        String idempotencyKey,
        BillingRunStatus status,
        int processedCount,
        int createdCount,
        int skippedCount,
        int failedCount,
        String errorSummary,
        Instant startedAt,
        Instant completedAt
) {
}
