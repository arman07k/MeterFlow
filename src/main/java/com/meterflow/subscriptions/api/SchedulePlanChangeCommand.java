package com.meterflow.subscriptions.api;

import java.util.UUID;

public record SchedulePlanChangeCommand(
        UUID targetPlanVersionId,
        Integer targetSeatQuantity,
        boolean replaceExisting,
        String idempotencyKey
) {
    public SchedulePlanChangeCommand(
            UUID targetPlanVersionId,
            Integer targetSeatQuantity,
            String idempotencyKey
    ) {
        this(targetPlanVersionId, targetSeatQuantity, false, idempotencyKey);
    }
}
