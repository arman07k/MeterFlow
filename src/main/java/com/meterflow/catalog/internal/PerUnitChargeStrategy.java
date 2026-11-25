package com.meterflow.catalog.internal;

import com.meterflow.catalog.api.ChargeCalculationContext;
import com.meterflow.catalog.api.ChargeCalculationOutcome;
import com.meterflow.catalog.api.ChargeCalculationStrategy;
import com.meterflow.catalog.api.ChargeDefinition;
import com.meterflow.catalog.api.ChargeTypes;
import com.meterflow.catalog.api.InvalidCatalogConfigurationException;
import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
class PerUnitChargeStrategy implements ChargeCalculationStrategy {

    @Override
    public String supportedChargeType() {
        return ChargeTypes.PER_UNIT;
    }

    @Override
    public void validate(ChargeDefinition charge, SupportedCurrency planCurrency) {
        if (charge.metricId() == null) {
            throw new InvalidCatalogConfigurationException("PER_UNIT charge requires a billable metricId");
        }
        if (charge.unitPrice() == null || charge.unitPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidCatalogConfigurationException("PER_UNIT charge requires unitPrice >= 0");
        }
        if (!charge.tiers().isEmpty()) {
            throw new InvalidCatalogConfigurationException("PER_UNIT charge must not define tiers");
        }
    }

    @Override
    public ChargeCalculationOutcome calculate(ChargeCalculationContext context) {
        BigDecimal unitPrice = context.charge().unitPrice();
        BigDecimal qty = context.billableQuantity();
        Money amount = qty.compareTo(BigDecimal.ZERO) == 0
                ? Money.zero(context.currency())
                : Money.of(unitPrice, context.currency()).multiply(qty);

        return new ChargeCalculationOutcome(
                amount,
                unitPrice,
                List.of(),
                Map.of("pricingModel", ChargeTypes.PER_UNIT)
        );
    }
}
