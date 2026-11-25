package com.meterflow.shared;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Canonical, immutable exact-decimal monetary value object across all MeterFlow modules (QA2, ADR-010).
 *
 * <p>Preserves exact {@link BigDecimal} precision during construction and arithmetic (no implicit
 * intermediate rounding). Callers invoke explicit rounding methods for intermediate ({@code scale=8}),
 * persisted ({@code scale=4}), and final payable minor-unit ({@code scale=2} paisa/cent) boundaries.
 */
@JsonAutoDetect(
        fieldVisibility = JsonAutoDetect.Visibility.NONE,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE
)
public final class Money implements Comparable<Money> {

    public static final int INTERMEDIATE_SCALE = 8;
    public static final int PERSISTED_SCALE = 4;
    public static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;

    private final BigDecimal amount;
    private final SupportedCurrency currency;

    private Money(BigDecimal amount, SupportedCurrency currency) {
        if (amount == null) {
            throw new InvalidMoneyException("Monetary amount must not be null");
        }
        if (currency == null) {
            throw new InvalidCurrencyException("Currency must not be null");
        }
        this.amount = normalizeNegativeZero(amount);
        this.currency = currency;
    }

    public static Money of(BigDecimal amount, SupportedCurrency currency) {
        return new Money(amount, currency);
    }

    public static Money of(BigDecimal amount, String currencyCode) {
        return new Money(amount, SupportedCurrency.fromCode(currencyCode));
    }

    public static Money of(String amount, SupportedCurrency currency) {
        return new Money(parseDecimal(amount), currency);
    }

    @JsonCreator
    public static Money of(
            @JsonProperty("amount") String amount,
            @JsonProperty("currency") String currencyCode
    ) {
        return new Money(parseDecimal(amount), SupportedCurrency.fromCode(currencyCode));
    }

    public static Money ofMinorUnits(long minorUnits, SupportedCurrency currency) {
        if (currency == null) {
            throw new InvalidCurrencyException("Currency must not be null");
        }
        return new Money(BigDecimal.valueOf(minorUnits, currency.minorUnitScale()), currency);
    }

    public static Money zero(SupportedCurrency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public static Money zero(String currencyCode) {
        return zero(SupportedCurrency.fromCode(currencyCode));
    }

    public static Money bdt(BigDecimal amount) {
        return of(amount, SupportedCurrency.BDT);
    }

    public static Money bdt(String amount) {
        return of(amount, SupportedCurrency.BDT);
    }

    public static Money usd(BigDecimal amount) {
        return of(amount, SupportedCurrency.USD);
    }

    public static Money usd(String amount) {
        return of(amount, SupportedCurrency.USD);
    }

    @JsonIgnore
    public BigDecimal amount() {
        return amount;
    }

    @JsonIgnore
    public SupportedCurrency currency() {
        return currency;
    }

    @JsonIgnore
    public String currencyCode() {
        return currency.code();
    }

    @JsonGetter("amount")
    public String amountAsPlainString() {
        return amount.toPlainString();
    }

    @JsonGetter("currency")
    public String serializedCurrency() {
        return currency.code();
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(this.amount.add(other.amount), this.currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(this.amount.subtract(other.amount), this.currency);
    }

    public Money negate() {
        return new Money(this.amount.negate(), this.currency);
    }

    public Money abs() {
        return new Money(this.amount.abs(), this.currency);
    }

    public Money multiply(BigDecimal multiplier) {
        if (multiplier == null) {
            throw new InvalidMoneyException("Multiplier must not be null");
        }
        return new Money(this.amount.multiply(multiplier), this.currency);
    }

    public Money multiply(long quantity) {
        return multiply(BigDecimal.valueOf(quantity));
    }

    public Money divide(BigDecimal divisor, int scale, RoundingMode roundingMode) {
        if (divisor == null) {
            throw new InvalidMoneyException("Divisor must not be null");
        }
        if (divisor.compareTo(BigDecimal.ZERO) == 0) {
            throw new InvalidMoneyException("Division by zero is not allowed");
        }
        validateRoundingParameters(scale, roundingMode);
        return new Money(this.amount.divide(divisor, scale, roundingMode), this.currency);
    }

    public Money round(int scale, RoundingMode roundingMode) {
        validateRoundingParameters(scale, roundingMode);
        return new Money(this.amount.setScale(scale, roundingMode), this.currency);
    }

    public Money roundToIntermediate() {
        return round(INTERMEDIATE_SCALE, ROUNDING_MODE);
    }

    public Money roundToPersisted() {
        return round(PERSISTED_SCALE, ROUNDING_MODE);
    }

    public Money roundToPayable() {
        return round(currency.minorUnitScale(), ROUNDING_MODE);
    }

    public boolean isZero() {
        return amount.compareTo(BigDecimal.ZERO) == 0;
    }

    public boolean isPositive() {
        return amount.compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean isNegative() {
        return amount.compareTo(BigDecimal.ZERO) < 0;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isGreaterThanOrEqual(Money other) {
        return compareTo(other) >= 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    public boolean isLessThanOrEqual(Money other) {
        return compareTo(other) <= 0;
    }

    public Money min(Money other) {
        return compareTo(other) <= 0 ? this : other;
    }

    public Money max(Money other) {
        return compareTo(other) >= 0 ? this : other;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return this.amount.compareTo(other.amount);
    }

    /**
     * Numerical equality (scale-insensitive) within the same currency:
     * {@code 10.00 BDT} equals {@code 10.0000 BDT}.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money other)) {
            return false;
        }
        return this.currency == other.currency && this.amount.compareTo(other.amount) == 0;
    }

    @Override
    public int hashCode() {
        BigDecimal normalized = isZero() ? BigDecimal.ZERO : amount.stripTrailingZeros();
        return Objects.hash(currency, normalized);
    }

    @JsonIgnore
    public String formatted() {
        return roundToPayable().amount().toPlainString() + " " + currency.code();
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.code();
    }

    private void requireSameCurrency(Money other) {
        if (other == null) {
            throw new InvalidMoneyException("Other monetary value must not be null");
        }
        if (this.currency != other.currency) {
            throw new CurrencyMismatchException(this.currency, other.currency);
        }
    }

    private static BigDecimal parseDecimal(String rawAmount) {
        if (rawAmount == null || rawAmount.isBlank()) {
            throw new InvalidMoneyException("Monetary amount string must not be null or blank");
        }
        try {
            return new BigDecimal(rawAmount.trim());
        } catch (NumberFormatException ex) {
            throw new InvalidMoneyException("Invalid monetary amount: '" + rawAmount + "'", ex);
        }
    }

    private static BigDecimal normalizeNegativeZero(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) == 0 && value.signum() < 0) {
            return value.abs();
        }
        return value;
    }

    private static void validateRoundingParameters(int scale, RoundingMode roundingMode) {
        if (scale < 0) {
            throw new InvalidMoneyException("Rounding scale must not be negative: " + scale);
        }
        if (roundingMode == null) {
            throw new InvalidMoneyException("RoundingMode must not be null");
        }
    }
}
