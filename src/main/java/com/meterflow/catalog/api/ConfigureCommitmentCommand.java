package com.meterflow.catalog.api;

import com.meterflow.shared.Money;

public record ConfigureCommitmentCommand(
        String targetChargeCode,
        Money minimumAmount
) {
}
