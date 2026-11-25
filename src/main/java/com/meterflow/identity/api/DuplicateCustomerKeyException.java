package com.meterflow.identity.api;

/**
 * Raised when creating a customer with an external key that already exists.
 */
public final class DuplicateCustomerKeyException extends IdentityException {

    public DuplicateCustomerKeyException(String message) {
        super(message);
    }
}
