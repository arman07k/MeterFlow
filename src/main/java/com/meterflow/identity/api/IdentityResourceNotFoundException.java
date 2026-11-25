package com.meterflow.identity.api;

/**
 * Raised when a requested customer, user, or membership resource does not exist.
 */
public final class IdentityResourceNotFoundException extends IdentityException {

    public IdentityResourceNotFoundException(String message) {
        super(message);
    }
}
