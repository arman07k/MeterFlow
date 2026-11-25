package com.meterflow.subscriptions.api;

public record MaintenanceProcessingSummary(
        int trialsConverted,
        int trialsExpired,
        int scheduledChangesApplied,
        int periodEndCancellationsApplied
) {
}
