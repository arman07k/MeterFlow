package com.meterflow.identity.api;

/**
 * Raised when an authenticated actor attempts to access a customer tenant or role-restricted
 * operation outside their authorized scope (QA4).
 */
public final class UnauthorizedCustomerAccessException extends IdentityException {

    public UnauthorizedCustomerAccessException(String message) {
        super(message);
    }
}
