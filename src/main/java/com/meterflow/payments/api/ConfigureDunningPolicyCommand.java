package com.meterflow.payments.api;

import java.util.List;

public record ConfigureDunningPolicyCommand(
        String policyCode,
        String name,
        List<Integer> retryOffsetsHours,
        Integer suspensionOffsetHours
) {
    public ConfigureDunningPolicyCommand {
        retryOffsetsHours = retryOffsetsHours == null ? List.of() : List.copyOf(retryOffsetsHours);
    }
}
