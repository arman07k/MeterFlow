package com.meterflow.shared;

/**
 * Raised when a monetary amount, factor, divisor, or rounding parameter is invalid.
 */
public final class InvalidMoneyException extends FinancialException {

    public InvalidMoneyException(String message) {
        super(message);
    }

    public InvalidMoneyException(String message, Throwable cause) {
        super(message, cause);
    }
}
