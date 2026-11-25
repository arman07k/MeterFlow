package com.meterflow.catalog.api;

import java.math.BigDecimal;

public record ChargeTierDefinition(
        int tierOrder,
        BigDecimal lowerBound,
        BigDecimal upperBound,
        BigDecimal unitPrice,
        BigDecimal flatFee,
        String description
) {
    public ChargeTierDefinition {
        flatFee = flatFee == null ? BigDecimal.ZERO : flatFee;
    }

    public boolean isUnbounded() {
        return upperBound == null;
    }
}
