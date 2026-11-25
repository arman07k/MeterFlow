package com.meterflow.catalog.api;

import java.util.UUID;

/**
 * Request to compute canonical recurring-period fixed charges (FLAT_FEE and contracted PER_SEAT)
 * for a published plan version without fabricating or prorating usage-based charges (ADR-012).
 */
public record RecurringQuoteRequest(
        UUID planVersionId,
        Integer seatQuantity
) {
}
