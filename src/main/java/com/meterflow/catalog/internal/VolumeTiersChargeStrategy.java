package com.meterflow.catalog.internal;

import com.meterflow.catalog.api.AppliedTierBreakdown;
import com.meterflow.catalog.api.ChargeCalculationContext;
import com.meterflow.catalog.api.ChargeCalculationOutcome;
import com.meterflow.catalog.api.ChargeCalculationStrategy;
import com.meterflow.catalog.api.ChargeDefinition;
import com.meterflow.catalog.api.ChargeTierDefinition;
import com.meterflow.catalog.api.ChargeTypes;
import com.meterflow.catalog.api.InvalidCatalogConfigurationException;
import com.meterflow.catalog.api.InvalidRatingContextException;
import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
class VolumeTiersChargeStrategy implements ChargeCalculationStrategy {

    @Override
    public String supportedChargeType() {
        return ChargeTypes.VOLUME_TIERS;
    }

    @Override
    public void validate(ChargeDefinition charge, SupportedCurrency planCurrency) {
        if (charge.metricId() == null) {
            throw new InvalidCatalogConfigurationException("VOLUME_TIERS charge requires a billable metricId");
        }
        TierValidationSupport.validateAndSortTiers(charge.tiers());
    }

    @Override
    public ChargeCalculationOutcome calculate(ChargeCalculationContext context) {
        List<ChargeTierDefinition> tiers = TierValidationSupport.validateAndSortTiers(context.charge().tiers());
        BigDecimal billableQty = context.billableQuantity();
        if (billableQty.compareTo(BigDecimal.ZERO) == 0) {
            return new ChargeCalculationOutcome(
                    Money.zero(context.currency()),
                    null,
                    List.of(),
                    Map.of("pricingModel", ChargeTypes.VOLUME_TIERS, "selectedTierOrder", 0)
            );
        }

        ChargeTierDefinition matched = null;
        for (ChargeTierDefinition tier : tiers) {
            boolean aboveLower = billableQty.compareTo(tier.lowerBound()) > 0;
            boolean withinUpper = tier.isUnbounded() || billableQty.compareTo(tier.upperBound()) <= 0;
            if (aboveLower && withinUpper) {
                matched = tier;
                break;
            }
        }
        if (matched == null) {
            throw new InvalidRatingContextException(
                    "Billable quantity " + billableQty + " exceeds maximum configured tier upper bound"
            );
        }

        Money tierAmount = Money.of(matched.unitPrice(), context.currency())
                .multiply(billableQty)
                .add(Money.of(matched.flatFee(), context.currency()));

        AppliedTierBreakdown breakdown = new AppliedTierBreakdown(
                matched.tierOrder(),
                matched.lowerBound(),
                matched.upperBound(),
                billableQty,
                matched.unitPrice(),
                matched.flatFee(),
                tierAmount
        );
        return new ChargeCalculationOutcome(
                tierAmount,
                matched.unitPrice(),
                List.of(breakdown),
                Map.of("pricingModel", ChargeTypes.VOLUME_TIERS, "selectedTierOrder", matched.tierOrder())
        );
    }
}
