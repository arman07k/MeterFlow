package com.meterflow.identity.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable public projection of an application user (never exposes password hashes).
 */
public record UserSummary(
        UUID id,
        String email,
        String fullName,
        PlatformRole role,
        UserStatus status,
        Instant createdAt
) {
}
