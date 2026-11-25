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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Mandatory FR3 PER_SEAT charge strategy supporting contracted seat quantities or metered seat gauges.
 */
@Component
class PerSeatChargeStrategy implements ChargeCalculationStrategy {

    @Override
    public String supportedChargeType() {
        return ChargeTypes.PER_SEAT;
    }

    @Override
    public void validate(ChargeDefinition charge, SupportedCurrency planCurrency) {
        if (charge.unitPrice() == null || charge.unitPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidCatalogConfigurationException("PER_SEAT charge requires unitPrice >= 0");
        }
        if (!charge.tiers().isEmpty()) {
            throw new InvalidCatalogConfigurationException("PER_SEAT charge must not define tiers");
        }
    }

    @Override
    public ChargeCalculationOutcome calculate(ChargeCalculationContext context) {
        BigDecimal seatUnitPrice = context.charge().unitPrice();
        BigDecimal billableSeats = context.billableQuantity();

        Money amount = billableSeats.compareTo(BigDecimal.ZERO) == 0
                ? Money.zero(context.currency())
                : Money.of(seatUnitPrice, context.currency()).multiply(billableSeats);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("pricingModel", ChargeTypes.PER_SEAT);
        metadata.put("seatSource", context.charge().metricId() == null ? "CONTRACTED_SEAT_QUANTITY" : "METERED_METRIC");
        metadata.put("billableSeats", billableSeats);

        return new ChargeCalculationOutcome(amount, seatUnitPrice, List.of(), metadata);
    }
}
