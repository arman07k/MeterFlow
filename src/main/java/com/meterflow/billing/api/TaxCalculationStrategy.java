package com.meterflow.billing.api;

import java.util.List;

/**
 * Extensible, deterministic tax calculation SPI (ADR-014).
 * Computes traceable tax lines strictly from explicit internal configuration (no external tax APIs).
 */
public interface TaxCalculationStrategy {

    List<CalculatedTaxLine> calculateTaxLines(TaxCalculationContext context);
}
