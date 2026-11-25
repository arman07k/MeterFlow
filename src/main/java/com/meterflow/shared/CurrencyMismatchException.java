package com.meterflow.shared;

/**
 * Raised when an operation attempts to combine or compare monetary values in different currencies.
 */
public final class CurrencyMismatchException extends FinancialException {

    public CurrencyMismatchException(SupportedCurrency left, SupportedCurrency right) {
        super("Currency mismatch: " + left.code() + " vs " + right.code());
    }
}
