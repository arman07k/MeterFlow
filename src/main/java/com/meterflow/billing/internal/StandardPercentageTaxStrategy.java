package com.meterflow.billing.internal;

import com.meterflow.billing.api.CalculatedTaxLine;
import com.meterflow.billing.api.TaxCalculationContext;
import com.meterflow.billing.api.TaxCalculationStrategy;
import com.meterflow.billing.api.TaxPolicySummary;
import com.meterflow.shared.Money;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Default deterministic percentage tax calculation strategy (ADR-010, ADR-014).
 * Zero external API calls; computes exact BigDecimal tax rounded once to persisted NUMERIC(19,4).
 */
@Component
class StandardPercentageTaxStrategy implements TaxCalculationStrategy {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override
    public List<CalculatedTaxLine> calculateTaxLines(TaxCalculationContext context) {
        TaxPolicySummary policy = context.policy();
        Money taxable = context.taxableAmount().roundToPersisted();
        if (policy == null || policy.taxExempt() || taxable.isZero() || taxable.isNegative()) {
            return List.of();
        }
        BigDecimal rate = policy.ratePercent();
        if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
            return List.of();
        }

        Money taxAmount = taxable
                .multiply(rate)
                .divide(HUNDRED, Money.INTERMEDIATE_SCALE, Money.ROUNDING_MODE)
                .roundToPersisted();

        return List.of(new CalculatedTaxLine(
                policy.id(),
                policy.taxName(),
                policy.jurisdiction(),
                taxable,
                rate,
                taxAmount
        ));
    }
}
