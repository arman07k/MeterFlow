package com.meterflow.metering.api;

public final class IdempotencyConflictException extends MeteringException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
