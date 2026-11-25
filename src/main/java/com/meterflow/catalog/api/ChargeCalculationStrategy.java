package com.meterflow.catalog.api;

import com.meterflow.shared.SupportedCurrency;

/**
 * Open/Closed Strategy SPI for charge pricing calculations (FR3, QA3, ADR-001, ADR-011).
 *
 * <p>Registering any new Spring bean implementing this interface automatically enables a new
 * charge type without modifying existing strategy classes or {@code PlanRatingComposite}.
 */
public interface ChargeCalculationStrategy {

    String supportedChargeType();

    void validate(ChargeDefinition charge, SupportedCurrency planCurrency);

    ChargeCalculationOutcome calculate(ChargeCalculationContext context);
}
