package com.meterflow.shared;

/**
 * Base runtime exception for MeterFlow financial kernel domain invariant violations (ADR-010).
 */
public abstract class FinancialException extends IllegalArgumentException {

    protected FinancialException(String message) {
        super(message);
    }

    protected FinancialException(String message, Throwable cause) {
        super(message, cause);
    }
}
