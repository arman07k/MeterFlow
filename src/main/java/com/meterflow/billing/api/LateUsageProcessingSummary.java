package com.meterflow.billing.api;

import java.util.List;
import java.util.UUID;

public record LateUsageProcessingSummary(
        int lateRecordsEvaluated,
        int draftsRefreshed,
        int adjustmentsCreated,
        int lateRecordsMarkedConsumed,
        List<UUID> createdAdjustmentIds
) {
    public LateUsageProcessingSummary {
        createdAdjustmentIds = createdAdjustmentIds == null ? List.of() : List.copyOf(createdAdjustmentIds);
    }
}
