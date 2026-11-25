package com.meterflow.webhooks.api;

public final class WebhookReplayNotAllowedException extends WebhookException {

    public WebhookReplayNotAllowedException(String message) {
        super(message);
    }
}
