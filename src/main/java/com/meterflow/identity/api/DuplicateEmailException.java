package com.meterflow.identity.api;

/**
 * Raised when creating a user with an email address that already exists (case-insensitive).
 */
public final class DuplicateEmailException extends IdentityException {

    public DuplicateEmailException(String message) {
        super(message);
    }
}
