package com.meterflow.catalog.api;

import com.meterflow.shared.SupportedCurrency;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PlanVersionDetail(
        UUID id,
        UUID planId,
        String planCode,
        String planName,
        SupportedCurrency currency,
        int versionNumber,
        PlanVersionStatus status,
        Instant effectiveFrom,
        Instant publishedAt,
        UUID clonedFromVersionId,
        List<ChargeDefinition> charges,
        List<FreeAllowanceDefinition> freeAllowances,
        List<MinimumCommitmentDefinition> minimumCommitments,
        Instant createdAt,
        Instant updatedAt
) {
    public PlanVersionDetail {
        charges = charges == null ? List.of() : List.copyOf(charges);
        freeAllowances = freeAllowances == null ? List.of() : List.copyOf(freeAllowances);
        minimumCommitments = minimumCommitments == null ? List.of() : List.copyOf(minimumCommitments);
    }
}
