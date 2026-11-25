package com.meterflow.subscriptions.api;

import java.util.Set;

/**
 * Authoritative subscription states and permitted transitions (FR4, ADR-012).
 */
public enum SubscriptionStatus {

    TRIALING,
    ACTIVE,
    PAUSED,
    PAST_DUE,
    SUSPENDED,
    CANCELLED,
    EXPIRED;

    public boolean isTerminal() {
        return this == CANCELLED || this == EXPIRED;
    }

    public boolean isOpen() {
        return !isTerminal();
    }

    public boolean canTransitionTo(SubscriptionStatus target) {
        if (target == null || target == this) {
            return false;
        }
        return allowedTargets().contains(target);
    }

    public Set<SubscriptionStatus> allowedTargets() {
        return switch (this) {
            case TRIALING -> Set.of(ACTIVE, EXPIRED, CANCELLED);
            case ACTIVE -> Set.of(PAUSED, PAST_DUE, SUSPENDED, CANCELLED);
            case PAUSED -> Set.of(ACTIVE, CANCELLED);
            case PAST_DUE -> Set.of(ACTIVE, SUSPENDED, CANCELLED);
            case SUSPENDED -> Set.of(ACTIVE, CANCELLED);
            case CANCELLED, EXPIRED -> Set.of();
        };
    }
}
