package com.meterflow.catalog.api;

import com.meterflow.shared.SupportedCurrency;
import java.time.Instant;

public record CreatePlanCommand(
        String planCode,
        String name,
        String description,
        SupportedCurrency currency,
        Instant initialEffectiveFrom
) {
}
