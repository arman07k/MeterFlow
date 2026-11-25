package com.meterflow.subscriptions.api;

public abstract class SubscriptionException extends RuntimeException {

    protected SubscriptionException(String message) {
        super(message);
    }
}
