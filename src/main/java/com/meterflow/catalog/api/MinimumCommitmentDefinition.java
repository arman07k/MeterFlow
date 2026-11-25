package com.meterflow.catalog.api;

import com.meterflow.shared.Money;
import java.util.UUID;

public record MinimumCommitmentDefinition(
        UUID id,
        UUID planChargeId,
        Money minimumAmount
) {
    public boolean isPlanLevel() {
        return planChargeId == null;
    }
}
