package com.meterflow.identity.api;

/**
 * Canonical Spring Security role constants for MeterFlow RBAC (QA4, ADR-006).
 */
public final class MeterFlowRoles {

    public static final String ADMIN = "ADMIN";
    public static final String OPERATOR = "OPERATOR";
    public static final String CUSTOMER = "CUSTOMER";

    private MeterFlowRoles() {
    }
}
