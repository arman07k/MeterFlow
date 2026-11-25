package com.meterflow.webhooks.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "meterflow.webhooks")
class WebhookProperties {

    private String encryptionKey = "";
    private boolean allowLocalTargets = false;
    private long workerFixedDelayMs = 15000L;
    private int batchSize = 50;
    private int timeoutMs = 5000;
    private int rotationGraceHours = 24;

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey != null ? encryptionKey : "";
    }

    public boolean isAllowLocalTargets() {
        return allowLocalTargets;
    }

    public void setAllowLocalTargets(boolean allowLocalTargets) {
        this.allowLocalTargets = allowLocalTargets;
    }

    public long getWorkerFixedDelayMs() {
        return workerFixedDelayMs;
    }

    public void setWorkerFixedDelayMs(long workerFixedDelayMs) {
        this.workerFixedDelayMs = workerFixedDelayMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getRotationGraceHours() {
        return rotationGraceHours;
    }

    public void setRotationGraceHours(int rotationGraceHours) {
        this.rotationGraceHours = rotationGraceHours;
    }
}
