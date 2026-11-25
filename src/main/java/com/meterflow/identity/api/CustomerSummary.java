package com.meterflow.identity.api;

import com.meterflow.shared.SupportedCurrency;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable public projection of a customer account.
 */
public record CustomerSummary(
        UUID id,
        String externalKey,
        String name,
        SupportedCurrency currency,
        CustomerStatus status,
        Instant createdAt
) {
}
