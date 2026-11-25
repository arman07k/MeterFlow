package com.meterflow.metering.api;

import java.util.UUID;

public record EventIngestionItemResult(
        int index,
        UUID eventId,
        String idempotencyKey,
        EventIngestionOutcome outcome,
        String errorCode,
        String message
) {
}
