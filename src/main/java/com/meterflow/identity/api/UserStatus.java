package com.meterflow.identity.api;

/**
 * Application user authentication status. Only {@link #ACTIVE} users may authenticate.
 */
public enum UserStatus {
    ACTIVE,
    DISABLED,
    SUSPENDED;

    public boolean canAuthenticate() {
        return this == ACTIVE;
    }
}
