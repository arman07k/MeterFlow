package com.meterflow.identity.api;

/**
 * Canonical platform roles backed by {@link MeterFlowRoles} to avoid duplicate role definitions.
 */
public enum PlatformRole {

    ADMIN(MeterFlowRoles.ADMIN),
    OPERATOR(MeterFlowRoles.OPERATOR),
    CUSTOMER(MeterFlowRoles.CUSTOMER);

    private final String roleName;

    PlatformRole(String roleName) {
        this.roleName = roleName;
    }

    public String roleName() {
        return roleName;
    }

    public String authority() {
        return MeterFlowRoles.ROLE_PREFIX + roleName;
    }

    public boolean isPlatformStaff() {
        return this == ADMIN || this == OPERATOR;
    }
}
