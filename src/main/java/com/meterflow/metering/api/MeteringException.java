package com.meterflow.metering.api;

public abstract class MeteringException extends RuntimeException {

    protected MeteringException(String message) {
        super(message);
    }
}
