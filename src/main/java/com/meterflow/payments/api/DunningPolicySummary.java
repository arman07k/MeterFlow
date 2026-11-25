package com.meterflow.payments.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DunningPolicySummary(
        UUID id,
        String policyCode,
        String name,
        List<Integer> retryOffsetsHours,
        int suspensionOffsetHours,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
    public DunningPolicySummary {
        retryOffsetsHours = retryOffsetsHours == null ? List.of() : List.copyOf(retryOffsetsHours);
    }
}
