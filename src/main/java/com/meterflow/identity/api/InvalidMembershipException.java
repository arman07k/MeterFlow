package com.meterflow.identity.api;

/**
 * Raised when a user-to-customer membership violates domain rules (e.g., assigning a customer
 * membership to a non-CUSTOMER platform user or to a closed customer).
 */
public final class InvalidMembershipException extends IdentityException {

    public InvalidMembershipException(String message) {
        super(message);
    }
}
