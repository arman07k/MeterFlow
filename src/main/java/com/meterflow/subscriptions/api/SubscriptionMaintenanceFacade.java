package com.meterflow.subscriptions.api;

import java.time.Instant;

/**
 * Repeatable, idempotent maintenance facade executing due trial expirations/conversions,
 * scheduled plan changes, and period-end cancellations (ADR-012).
 */
public interface SubscriptionMaintenanceFacade {

    MaintenanceProcessingSummary processDueOperations();

    MaintenanceProcessingSummary processDueOperationsAt(Instant referenceTimeUtc);
}
