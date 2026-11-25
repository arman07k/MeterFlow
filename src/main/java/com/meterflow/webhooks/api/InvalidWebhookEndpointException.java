package com.meterflow.webhooks.api;

public final class InvalidWebhookEndpointException extends WebhookException {

    public InvalidWebhookEndpointException(String message) {
        super(message);
    }
}
