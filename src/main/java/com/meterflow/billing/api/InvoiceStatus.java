package com.meterflow.billing.api;

import java.util.Set;

/**
 * Authoritative invoice states and permitted transitions (FR5, ADR-014).
 */
public enum InvoiceStatus {

    DRAFT,
    FINALIZED,
    PAID,
    PAST_DUE,
    WRITTEN_OFF,
    VOID;

    public boolean isImmutableFinancialRecord() {
        return this != DRAFT;
    }

    public boolean isEligibleForCreditNote() {
        return this == FINALIZED || this == PAID || this == PAST_DUE;
    }

    public boolean canTransitionTo(InvoiceStatus target) {
        if (target == null || target == this) {
            return false;
        }
        return allowedTargets().contains(target);
    }

    public Set<InvoiceStatus> allowedTargets() {
        return switch (this) {
            case DRAFT -> Set.of(FINALIZED, VOID);
            case FINALIZED -> Set.of(PAID, PAST_DUE, WRITTEN_OFF, VOID);
            case PAST_DUE -> Set.of(PAID, WRITTEN_OFF, VOID);
            case PAID, WRITTEN_OFF, VOID -> Set.of();
        };
    }
}
