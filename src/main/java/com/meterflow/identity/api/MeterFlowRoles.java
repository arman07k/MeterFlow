package com.meterflow.identity.api;

/**
 * Canonical Spring Security role constants for MeterFlow RBAC (QA4, ADR-006).
 */
public final class MeterFlowRoles {

    public static final String ROLE_PREFIX = "ROLE_";

    public static final String ADMIN = "ADMIN";
    public static final String OPERATOR = "OPERATOR";
    public static final String CUSTOMER = "CUSTOMER";

    public static final String ROLE_ADMIN = ROLE_PREFIX + ADMIN;
    public static final String ROLE_OPERATOR = ROLE_PREFIX + OPERATOR;
    public static final String ROLE_CUSTOMER = ROLE_PREFIX + CUSTOMER;

    private MeterFlowRoles() {
    }
}
