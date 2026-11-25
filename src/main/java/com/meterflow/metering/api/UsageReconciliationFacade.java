package com.meterflow.metering.api;

import java.time.Instant;
import java.util.UUID;

public interface UsageReconciliationFacade {

    RollupReconciliationResult reconcileRollups(
            UUID customerId,
            UUID metricId,
            Instant windowStart,
            Instant windowEnd
    );

    int recoverPendingRollups(int maxEvents);
}
