package com.meterflow.billing.api;

/**
 * Public read-only facade for FR10 Historical Invoice Simulation & Plan-Version Comparison.
 */
public interface HistoricalInvoiceSimulationFacade {

    HistoricalInvoiceComparisonResult simulateHistoricalInvoice(HistoricalInvoiceSimulationRequest request);
}
