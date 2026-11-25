package com.meterflow.billing.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Request to simulate a historical billing invoice or historical period against an alternative plan version (FR10).
 * Either {@code originalInvoiceId} OR ({@code customerId}, {@code subscriptionId}, {@code periodStart}, {@code periodEnd})
 * must be supplied alongside {@code targetPlanVersionId}.
 */
public record HistoricalInvoiceSimulationRequest(
        UUID originalInvoiceId,
        UUID customerId,
        UUID subscriptionId,
        Instant periodStart,
        Instant periodEnd,
        UUID targetPlanVersionId,
        Integer seatQuantityOverride
) {
}
