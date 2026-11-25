package com.meterflow.catalog.api;

import com.meterflow.shared.SupportedCurrency;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ChargeCalculationContext(
        UUID customerId,
        UUID planVersionId,
        SupportedCurrency currency,
        ChargeDefinition charge,
        BigDecimal originalQuantity,
        BigDecimal freeAllowanceApplied,
        BigDecimal billableQuantity,
        Integer seatQuantity,
        Instant periodStart,
        Instant periodEnd
) {
}
