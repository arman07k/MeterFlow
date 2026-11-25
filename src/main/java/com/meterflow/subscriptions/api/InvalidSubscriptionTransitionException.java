package com.meterflow.subscriptions.api;

public final class InvalidSubscriptionTransitionException extends SubscriptionException {

    public InvalidSubscriptionTransitionException(String message) {
        super(message);
    }
}
