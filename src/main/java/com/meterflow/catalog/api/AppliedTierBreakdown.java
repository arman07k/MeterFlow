package com.meterflow.catalog.api;

import com.meterflow.shared.Money;
import java.math.BigDecimal;

public record AppliedTierBreakdown(
        int tierOrder,
        BigDecimal lowerBound,
        BigDecimal upperBound,
        BigDecimal unitsBilled,
        BigDecimal unitPrice,
        BigDecimal flatFee,
        Money tierAmount
) {
}
