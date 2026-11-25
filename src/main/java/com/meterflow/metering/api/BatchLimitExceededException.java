package com.meterflow.metering.api;

public final class BatchLimitExceededException extends MeteringException {

    public BatchLimitExceededException(String message) {
        super(message);
    }
}
