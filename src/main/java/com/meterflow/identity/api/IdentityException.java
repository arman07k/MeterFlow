package com.meterflow.identity.api;

/**
 * Base runtime exception for identity and tenant authorization domain errors.
 */
public abstract class IdentityException extends RuntimeException {

    protected IdentityException(String message) {
        super(message);
    }
}
