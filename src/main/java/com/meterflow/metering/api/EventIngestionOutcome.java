package com.meterflow.metering.api;

public enum EventIngestionOutcome {
    ACCEPTED,
    ACCEPTED_LATE,
    DUPLICATE,
    CONFLICT,
    REJECTED
}
