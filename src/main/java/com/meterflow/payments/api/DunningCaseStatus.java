package com.meterflow.payments.api;

import java.util.Set;

/**
 * Authoritative dunning case states and permitted transitions (FR8, ADR-007, ADR-015).
 */
public enum DunningCaseStatus {

    RETRY_SCHEDULED,
    FINAL_WARNING,
    SUSPENDED,
    RECOVERED,
    CANCELLED;

    public boolean isOpen() {
        return this == RETRY_SCHEDULED || this == FINAL_WARNING || this == SUSPENDED;
    }

    public boolean canTransitionTo(DunningCaseStatus target) {
        if (target == null || target == this) {
            return false;
        }
        return allowedTargets().contains(target);
    }

    public Set<DunningCaseStatus> allowedTargets() {
        return switch (this) {
            case RETRY_SCHEDULED -> Set.of(FINAL_WARNING, RECOVERED, CANCELLED);
            case FINAL_WARNING -> Set.of(SUSPENDED, RECOVERED, CANCELLED);
            case SUSPENDED -> Set.of(RECOVERED, CANCELLED);
            case RECOVERED, CANCELLED -> Set.of();
        };
    }
}
