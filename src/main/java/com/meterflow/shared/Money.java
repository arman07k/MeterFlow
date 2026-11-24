package com.meterflow.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Immutable exact-decimal monetary value primitive enforcing NUMERIC(19,4)
 * HALF_UP persisted precision across all MeterFlow modules (QA2).
 */
public record Money(BigDecimal amount, String currencyCode) {

    public static final int PERSISTED_SCALE = 4;
    public static final int INTERMEDIATE_SCALE = 8;
    public static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;

    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currencyCode, "currencyCode must not be null");
        String normalizedCode = currencyCode.trim().toUpperCase();
        Currency.getInstance(normalizedCode);
        currencyCode = normalizedCode;
        amount = amount.setScale(PERSISTED_SCALE, ROUNDING_MODE);
    }

    public static Money zero(String currencyCode) {
        return new Money(BigDecimal.ZERO, currencyCode);
    }

    public static Money of(BigDecimal amount, String currencyCode) {
        return new Money(amount, currencyCode);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(this.amount.add(other.amount), this.currencyCode);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(this.amount.subtract(other.amount), this.currencyCode);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other money value must not be null");
        if (!this.currencyCode.equals(other.currencyCode)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: " + this.currencyCode + " vs " + other.currencyCode
            );
        }
    }
}
