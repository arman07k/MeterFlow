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
class FlatFeeChargeStrategy implements ChargeCalculationStrategy {

    @Override
    public String supportedChargeType() {
        return ChargeTypes.FLAT_FEE;
    }

    @Override
    public void validate(ChargeDefinition charge, SupportedCurrency planCurrency) {
        if (charge.flatAmount() == null || charge.flatAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidCatalogConfigurationException("FLAT_FEE charge requires flatAmount >= 0");
        }
        if (!charge.tiers().isEmpty()) {
            throw new InvalidCatalogConfigurationException("FLAT_FEE charge must not define tiers");
        }
    }

    @Override
    public ChargeCalculationOutcome calculate(ChargeCalculationContext context) {
        BigDecimal flat = context.charge().flatAmount();
        Money amount = Money.of(flat, context.currency());
        return new ChargeCalculationOutcome(
                amount,
                flat,
                List.of(),
                Map.of("pricingModel", ChargeTypes.FLAT_FEE)
        );
    }
}
