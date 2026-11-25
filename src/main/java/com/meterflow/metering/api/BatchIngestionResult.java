package com.meterflow.metering.api;

import java.time.Instant;
import java.util.List;

public record BatchIngestionResult(
        int totalReceived,
        int acceptedCount,
        int lateAcceptedCount,
        int duplicateCount,
        int conflictCount,
        int rejectedCount,
        Instant ingestedAt,
        List<EventIngestionItemResult> results
) {
    public BatchIngestionResult {
        results = results == null ? List.of() : List.copyOf(results);
    }
}
