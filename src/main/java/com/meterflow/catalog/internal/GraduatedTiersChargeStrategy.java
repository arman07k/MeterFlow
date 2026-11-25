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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
class GraduatedTiersChargeStrategy implements ChargeCalculationStrategy {

    @Override
    public String supportedChargeType() {
        return ChargeTypes.GRADUATED_TIERS;
    }

    @Override
    public void validate(ChargeDefinition charge, SupportedCurrency planCurrency) {
        if (charge.metricId() == null) {
            throw new InvalidCatalogConfigurationException("GRADUATED_TIERS charge requires a billable metricId");
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
                    Map.of("pricingModel", ChargeTypes.GRADUATED_TIERS, "tiersMatched", 0)
            );
        }

        ChargeTierDefinition lastTier = tiers.getLast();
        if (!lastTier.isUnbounded() && billableQty.compareTo(lastTier.upperBound()) > 0) {
            throw new InvalidRatingContextException(
                    "Billable quantity " + billableQty + " exceeds maximum configured tier upper bound " + lastTier.upperBound()
            );
        }

        Money total = Money.zero(context.currency());
        List<AppliedTierBreakdown> breakdowns = new ArrayList<>();
        for (ChargeTierDefinition tier : tiers) {
            if (billableQty.compareTo(tier.lowerBound()) <= 0) {
                break;
            }
            BigDecimal effectiveUpper = tier.isUnbounded()
                    ? billableQty
                    : billableQty.min(tier.upperBound());
            BigDecimal unitsInTier = effectiveUpper.subtract(tier.lowerBound());
            Money tierUsageCost = Money.of(tier.unitPrice(), context.currency()).multiply(unitsInTier);
            Money tierTotal = tierUsageCost.add(Money.of(tier.flatFee(), context.currency()));

            total = total.add(tierTotal);
            breakdowns.add(new AppliedTierBreakdown(
                    tier.tierOrder(),
                    tier.lowerBound(),
                    tier.upperBound(),
                    unitsInTier,
                    tier.unitPrice(),
                    tier.flatFee(),
                    tierTotal
            ));
        }

        BigDecimal marginalUnitPrice = breakdowns.isEmpty() ? null : breakdowns.getLast().unitPrice();
        return new ChargeCalculationOutcome(
                total,
                marginalUnitPrice,
                breakdowns,
                Map.of("pricingModel", ChargeTypes.GRADUATED_TIERS, "tiersMatched", breakdowns.size())
        );
    }
}
