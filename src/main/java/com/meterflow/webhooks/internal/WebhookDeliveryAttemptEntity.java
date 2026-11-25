package com.meterflow.webhooks.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_delivery_attempts")
class WebhookDeliveryAttemptEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "webhook_delivery_id", nullable = false, updatable = false)
    private UUID webhookDeliveryId;

    @Column(name = "webhook_endpoint_id", nullable = false, updatable = false)
    private UUID webhookEndpointId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    @Column(name = "is_manual_replay", nullable = false, updatable = false)
    private boolean manualReplay;

    @Column(name = "replayed_by_user_id", updatable = false)
    private UUID replayedByUserId;

    @Column(name = "signature_timestamp", nullable = false, updatable = false)
    private long signatureTimestamp;

    @Column(name = "http_status", updatable = false)
    private Integer httpStatus;

    @Column(name = "duration_ms", nullable = false, updatable = false)
    private int durationMs;

    @Column(name = "response_excerpt", length = 500, updatable = false)
    private String responseExcerpt;

    @Column(name = "error_reason", length = 500, updatable = false)
    private String errorReason;

    @Column(name = "attempted_at", nullable = false, updatable = false)
    private Instant attemptedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WebhookDeliveryAttemptEntity() {
    }

    WebhookDeliveryAttemptEntity(
            UUID webhookDeliveryId,
            UUID webhookEndpointId,
            UUID customerId,
            int attemptNumber,
            boolean manualReplay,
            UUID replayedByUserId,
            long signatureTimestamp,
            Integer httpStatus,
            int durationMs,
            String responseExcerpt,
            String errorReason,
            Instant attemptedAt
    ) {
        this.webhookDeliveryId = webhookDeliveryId;
        this.webhookEndpointId = webhookEndpointId;
        this.customerId = customerId;
        this.attemptNumber = attemptNumber;
        this.manualReplay = manualReplay;
        this.replayedByUserId = replayedByUserId;
        this.signatureTimestamp = signatureTimestamp;
        this.httpStatus = httpStatus;
        this.durationMs = Math.max(0, durationMs);
        this.responseExcerpt = truncate(responseExcerpt, 500);
        this.errorReason = truncate(errorReason, 500);
        this.attemptedAt = attemptedAt;
        this.createdAt = attemptedAt;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    UUID getId() {
        return id;
    }

    UUID getWebhookDeliveryId() {
        return webhookDeliveryId;
    }

    UUID getWebhookEndpointId() {
        return webhookEndpointId;
    }

    UUID getCustomerId() {
        return customerId;
    }

    int getAttemptNumber() {
        return attemptNumber;
    }

    boolean isManualReplay() {
        return manualReplay;
    }

    UUID getReplayedByUserId() {
        return replayedByUserId;
    }

    long getSignatureTimestamp() {
        return signatureTimestamp;
    }

    Integer getHttpStatus() {
        return httpStatus;
    }

    int getDurationMs() {
        return durationMs;
    }

    String getResponseExcerpt() {
        return responseExcerpt;
    }

    String getErrorReason() {
        return errorReason;
    }

    Instant getAttemptedAt() {
        return attemptedAt;
    }
}
