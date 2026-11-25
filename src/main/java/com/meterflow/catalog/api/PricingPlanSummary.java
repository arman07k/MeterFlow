package com.meterflow.catalog.api;

import com.meterflow.shared.SupportedCurrency;
import java.time.Instant;
import java.util.UUID;

public record PricingPlanSummary(
        UUID id,
        String planCode,
        String name,
        String description,
        SupportedCurrency currency,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
}
