package com.meterflow.identity.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable public projection of a user-to-customer membership.
 */
public record CustomerMembershipSummary(
        UUID id,
        UUID userId,
        UUID customerId,
        MembershipRole membershipRole,
        MembershipStatus status,
        Instant createdAt
) {
}
