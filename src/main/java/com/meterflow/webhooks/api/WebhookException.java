package com.meterflow.webhooks.api;

public abstract class WebhookException extends RuntimeException {

    protected WebhookException(String message) {
        super(message);
    }

    protected WebhookException(String message, Throwable cause) {
        super(message, cause);
    }
}
