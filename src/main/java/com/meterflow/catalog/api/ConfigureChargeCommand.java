package com.meterflow.catalog.api;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ConfigureChargeCommand(
        int displayOrder,
        String chargeCode,
        String description,
        String chargeType,
        UUID metricId,
        BigDecimal flatAmount,
        BigDecimal unitPrice,
        BigDecimal packageSize,
        BigDecimal packagePrice,
        List<ChargeTierDefinition> tiers,
        Map<String, Object> chargeConfig
) {
    public ConfigureChargeCommand {
        tiers = tiers == null ? List.of() : List.copyOf(tiers);
        chargeConfig = chargeConfig == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(chargeConfig));
    }
}
