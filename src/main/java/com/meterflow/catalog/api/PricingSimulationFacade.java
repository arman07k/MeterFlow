package com.meterflow.catalog.api;

/**
 * Read-only pricing simulation facade reusing {@link RatingEngineFacade} with zero persistence side effects (FR10).
 */
public interface PricingSimulationFacade {

    PlanRatingResult simulate(PricingSimulationRequest request);
}
