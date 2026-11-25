package com.meterflow.subscriptions.internal;

import com.meterflow.shared.BillingPeriod;
import com.meterflow.shared.CurrencyMismatchException;
import com.meterflow.shared.Money;
import com.meterflow.subscriptions.api.InvalidSubscriptionRequestException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic exact-seconds UTC proration calculator using canonical {@link Money} and
 * {@link BigDecimal} exclusively (ADR-010, ADR-012).
 *
 * <p>Avoids premature intermediate rounding by multiplying amounts by exact remaining seconds
 * before dividing by total period seconds and rounding once to persisted {@code NUMERIC(19,4)}.
 */
final class ProrationCalculator {

    static final String POLICY_EXACT_SECONDS_V1 = "EXACT_SECONDS_V1";

    private ProrationCalculator() {
    }

    record CalculationResult(
            long totalPeriodSeconds,
            long remainingSeconds,
            BigDecimal prorationFactor,
            Money previousPlanRecurringAmount,
            Money newPlanRecurringAmount,
            Money creditAmount,
            Money debitAmount,
            Money netProrationAmount,
            List<String> nonProratableUsageCharges,
            String calculationPolicy
    ) {
    }

    static CalculationResult calculate(
            BillingPeriod period,
            Instant effectiveAt,
            Money previousRecurringAmount,
            Money newRecurringAmount,
            List<String> nonProratableUsageCharges
    ) {
        Objects.requireNonNull(period, "period must not be null");
        Objects.requireNonNull(effectiveAt, "effectiveAt must not be null");
        Objects.requireNonNull(previousRecurringAmount, "previousRecurringAmount must not be null");
        Objects.requireNonNull(newRecurringAmount, "newRecurringAmount must not be null");

        if (previousRecurringAmount.currency() != newRecurringAmount.currency()) {
            throw new CurrencyMismatchException(previousRecurringAmount.currency(), newRecurringAmount.currency());
        }
        if (previousRecurringAmount.isNegative() || newRecurringAmount.isNegative()) {
            throw new InvalidSubscriptionRequestException("Recurring plan amounts for proration must not be negative");
        }
        if (effectiveAt.isBefore(period.start()) || effectiveAt.isAfter(period.end())) {
            throw new InvalidSubscriptionRequestException(
                    "effectiveAt must fall within current billing period [" + period.start() + ", " + period.end() + "]"
            );
        }

        long totalSeconds = period.totalSeconds();
        long remainingSeconds = period.remainingSecondsFrom(effectiveAt);
        BigDecimal totalBd = BigDecimal.valueOf(totalSeconds);
        BigDecimal remainingBd = BigDecimal.valueOf(remainingSeconds);

        BigDecimal factor = remainingBd.divide(totalBd, Money.INTERMEDIATE_SCALE, Money.ROUNDING_MODE);

        Money prevPersisted = previousRecurringAmount.roundToPersisted();
        Money newPersisted = newRecurringAmount.roundToPersisted();

        Money credit = remainingSeconds == 0
                ? Money.zero(previousRecurringAmount.currency()).roundToPersisted()
                : previousRecurringAmount
                        .multiply(remainingBd)
                        .divide(totalBd, Money.INTERMEDIATE_SCALE, Money.ROUNDING_MODE)
                        .roundToPersisted();

        Money debit = remainingSeconds == 0
                ? Money.zero(newRecurringAmount.currency()).roundToPersisted()
                : newRecurringAmount
                        .multiply(remainingBd)
                        .divide(totalBd, Money.INTERMEDIATE_SCALE, Money.ROUNDING_MODE)
                        .roundToPersisted();

        Money net = debit.subtract(credit).roundToPersisted();

        return new CalculationResult(
                totalSeconds,
                remainingSeconds,
                factor,
                prevPersisted,
                newPersisted,
                credit,
                debit,
                net,
                nonProratableUsageCharges == null ? List.of() : List.copyOf(nonProratableUsageCharges),
                POLICY_EXACT_SECONDS_V1
        );
    }
}
