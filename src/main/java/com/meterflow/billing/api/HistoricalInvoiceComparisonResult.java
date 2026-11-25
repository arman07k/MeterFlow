package com.meterflow.billing.api;

import com.meterflow.catalog.api.PlanVersionStatus;
import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only comparison between a historical finalized invoice (or historical period) and
 * an alternative plan version rated over the exact same historical usage (FR10).
 */
public record HistoricalInvoiceComparisonResult(
        boolean simulation,
        boolean financialRecordMutated,
        String notice,
        UUID customerId,
        UUID subscriptionId,
        UUID originalInvoiceId,
        String originalInvoiceNumber,
        Instant periodStart,
        Instant periodEnd,
        SupportedCurrency currency,
        int seatQuantityUsed,
        UUID originalPlanVersionId,
        String originalPlanLabel,
        UUID simulatedPlanVersionId,
        String simulatedPlanLabel,
        PlanVersionStatus simulatedPlanStatus,
        Money originalSubtotal,
        Money originalTaxTotal,
        Money originalTotal,
        Money originalCreditsApplied,
        Money originalPayableDue,
        Money simulatedSubtotal,
        Money simulatedPlanMinimumTrueUp,
        Money simulatedTaxTotal,
        Money simulatedTotal,
        Money simulatedCreditsApplied,
        Money simulatedPayableDue,
        Money subtotalDelta,
        Money taxDelta,
        Money totalDelta,
        BigDecimal totalDeltaPercent,
        List<InvoiceLineRecord> originalLines,
        List<SimulatedChargeLine> simulatedLines,
        List<CalculatedTaxLine> simulatedTaxLines,
        List<String> dataWarnings
) {
    public HistoricalInvoiceComparisonResult {
        originalLines = originalLines == null ? List.of() : List.copyOf(originalLines);
        simulatedLines = simulatedLines == null ? List.of() : List.copyOf(simulatedLines);
        simulatedTaxLines = simulatedTaxLines == null ? List.of() : List.copyOf(simulatedTaxLines);
        dataWarnings = dataWarnings == null ? List.of() : List.copyOf(dataWarnings);
    }
}
