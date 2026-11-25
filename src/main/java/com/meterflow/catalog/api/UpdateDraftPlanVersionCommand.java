package com.meterflow.catalog.api;

import java.time.Instant;
import java.util.List;

public record UpdateDraftPlanVersionCommand(
        Instant effectiveFrom,
        List<ConfigureChargeCommand> charges,
        List<ConfigureAllowanceCommand> freeAllowances,
        List<ConfigureCommitmentCommand> minimumCommitments
) {
    public UpdateDraftPlanVersionCommand {
        charges = charges == null ? List.of() : List.copyOf(charges);
        freeAllowances = freeAllowances == null ? List.of() : List.copyOf(freeAllowances);
        minimumCommitments = minimumCommitments == null ? List.of() : List.copyOf(minimumCommitments);
    }
}
