package com.meterflow.catalog.api;

/**
 * Plan version status enforcing DRAFT -> PUBLISHED -> ARCHIVED immutability (FR10).
 */
public enum PlanVersionStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED;

    public boolean isMutable() {
        return this == DRAFT;
    }
}
