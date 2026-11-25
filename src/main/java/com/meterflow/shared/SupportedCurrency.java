package com.meterflow.shared;

import java.util.Currency;
import java.util.Locale;

/**
 * Supported settlement currencies for MeterFlow (ADR-010).
 * No automatic FX conversion is permitted across currencies.
 */
public enum SupportedCurrency {

    BDT("BDT", 2, "Bangladeshi Taka"),
    USD("USD", 2, "US Dollar");

    private final String code;
    private final int minorUnitScale;
    private final String displayName;

    SupportedCurrency(String code, int minorUnitScale, String displayName) {
        this.code = code;
        this.minorUnitScale = minorUnitScale;
        this.displayName = displayName;
    }

    public String code() {
        return code;
    }

    public int minorUnitScale() {
        return minorUnitScale;
    }

    public String displayName() {
        return displayName;
    }

    public Currency toJavaCurrency() {
        return Currency.getInstance(code);
    }

    public static SupportedCurrency fromCode(String currencyCode) {
        if (currencyCode == null || currencyCode.isBlank()) {
            throw new InvalidCurrencyException("Currency code must not be null or blank");
        }
        String normalized = currencyCode.trim().toUpperCase(Locale.ROOT);
        for (SupportedCurrency candidate : values()) {
            if (candidate.code.equals(normalized)) {
                return candidate;
            }
        }
        throw new InvalidCurrencyException(
                "Unsupported currency code '" + normalized + "'. Supported currencies: BDT, USD"
        );
    }
}
