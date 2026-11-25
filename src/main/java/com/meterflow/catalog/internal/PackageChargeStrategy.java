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
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * PACKAGE charge strategy implementing ADR-011 ceiling partial-package rounding:
 * {@code packages = CEIL(billableQty / packageSize)} for {@code billableQty > 0}, else {@code 0}.
 */
@Component
class PackageChargeStrategy implements ChargeCalculationStrategy {

    @Override
    public String supportedChargeType() {
        return ChargeTypes.PACKAGE;
    }

    @Override
    public void validate(ChargeDefinition charge, SupportedCurrency planCurrency) {
        if (charge.metricId() == null) {
            throw new InvalidCatalogConfigurationException("PACKAGE charge requires a billable metricId");
        }
        if (charge.packageSize() == null || charge.packageSize().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidCatalogConfigurationException("PACKAGE charge requires packageSize > 0");
        }
        if (charge.packagePrice() == null || charge.packagePrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidCatalogConfigurationException("PACKAGE charge requires packagePrice >= 0");
        }
        if (!charge.tiers().isEmpty()) {
            throw new InvalidCatalogConfigurationException("PACKAGE charge must not define tiers");
        }
    }

    @Override
    public ChargeCalculationOutcome calculate(ChargeCalculationContext context) {
        BigDecimal packageSize = context.charge().packageSize();
        BigDecimal packagePrice = context.charge().packagePrice();
        BigDecimal billableQty = context.billableQuantity();

        BigDecimal packagesBilled = billableQty.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : billableQty.divide(packageSize, 0, RoundingMode.CEILING);

        Money amount = packagesBilled.compareTo(BigDecimal.ZERO) == 0
                ? Money.zero(context.currency())
                : Money.of(packagePrice, context.currency()).multiply(packagesBilled);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("pricingModel", ChargeTypes.PACKAGE);
        metadata.put("packageSize", packageSize);
        metadata.put("packagePrice", packagePrice);
        metadata.put("packagesBilled", packagesBilled.longValueExact());
        metadata.put("partialPackagePolicy", "CEILING");

        return new ChargeCalculationOutcome(amount, packagePrice, List.of(), metadata);
    }
}
