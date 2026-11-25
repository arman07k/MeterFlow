package com.meterflow.subscriptions.api;

public final class ConflictingSubscriptionException extends SubscriptionException {

    public ConflictingSubscriptionException(String message) {
        super(message);
    }
}
