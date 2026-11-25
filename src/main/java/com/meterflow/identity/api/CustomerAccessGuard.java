package com.meterflow.identity.api;

import java.util.Set;
import java.util.UUID;

/**
 * Reusable tenant-isolation and role authorization guard for all MeterFlow business modules (QA4).
 *
 * <p>Resolves authorization strictly from the authenticated Spring Security principal and active
 * database memberships, never trusting request-supplied customer IDs as proof of ownership.
 */
public interface CustomerAccessGuard {

    AuthenticatedActor requireAuthenticatedActor();

    void requireCustomerAccess(UUID targetCustomerId);

    boolean canAccessCustomer(UUID targetCustomerId);

    Set<UUID> requireCustomerScope();

    void requireAnyRole(PlatformRole... allowedRoles);
}
