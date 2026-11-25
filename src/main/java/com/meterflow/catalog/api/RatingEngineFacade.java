package com.meterflow.catalog.api;

/**
 * Single authoritative rating facade reused across invoicing, live estimates, subscriptions, and simulations (ADR-001, ADR-011, ADR-012).
 */
public interface RatingEngineFacade {

    PlanRatingResult rate(RatingRequest request);

    RecurringPlanQuote rateRecurringCharges(RecurringQuoteRequest request);
}
