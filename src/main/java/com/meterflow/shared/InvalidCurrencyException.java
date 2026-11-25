package com.meterflow.shared;

/**
 * Raised when a currency code is missing, malformed, or outside supported currencies (BDT, USD).
 */
public final class InvalidCurrencyException extends FinancialException {

    public InvalidCurrencyException(String message) {
        super(message);
    }
}
