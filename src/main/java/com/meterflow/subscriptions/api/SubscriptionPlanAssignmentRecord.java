package com.meterflow.subscriptions.api;

import java.time.Instant;
import java.util.UUID;

public record SubscriptionPlanAssignmentRecord(
        UUID id,
        UUID subscriptionId,
        UUID planVersionId,
        int seatQuantity,
        Instant effectiveFrom,
        Instant effectiveTo,
        String assignmentReason,
        Instant createdAt
) {
}
