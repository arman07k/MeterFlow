package com.meterflow.identity.api;

import java.util.Set;
import java.util.UUID;

/**
 * Immutable snapshot of the currently authenticated principal resolved by the identity module.
 */
public record AuthenticatedActor(
        UUID userId,
        String email,
        String fullName,
        PlatformRole role,
        UserStatus status,
        Set<UUID> authorizedCustomerIds
) {

    public AuthenticatedActor {
        authorizedCustomerIds = authorizedCustomerIds == null ? Set.of() : Set.copyOf(authorizedCustomerIds);
    }

    public boolean isPlatformStaff() {
        return role != null && role.isPlatformStaff();
    }

    public boolean hasCustomerMembership(UUID customerId) {
        return customerId != null && authorizedCustomerIds.contains(customerId);
    }
}
