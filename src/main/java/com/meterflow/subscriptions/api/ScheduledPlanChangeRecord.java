package com.meterflow.subscriptions.api;

import java.time.Instant;
import java.util.UUID;

public record ScheduledPlanChangeRecord(
        UUID id,
        UUID subscriptionId,
        UUID currentPlanVersionId,
        UUID targetPlanVersionId,
        int targetSeatQuantity,
        PlanChangeDirection changeDirection,
        Instant effectiveAt,
        ScheduledChangeStatus status,
        String idempotencyKey,
        Instant appliedAt,
        Instant cancelledAt,
        Instant createdAt
) {
}
