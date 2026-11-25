package com.meterflow.subscriptions.api;

import java.time.Instant;
import java.util.UUID;

public record ImmediatePlanChangeCommand(
        UUID targetPlanVersionId,
        Integer targetSeatQuantity,
        Instant effectiveAt,
        String idempotencyKey
) {
}
