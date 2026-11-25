package com.meterflow.webhooks.internal;

import com.meterflow.webhooks.api.WebhookDeliveryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_deliveries")
class WebhookDeliveryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "webhook_endpoint_id", nullable = false, updatable = false)
    private UUID webhookEndpointId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "source_event_id", nullable = false, updatable = false)
    private UUID sourceEventId;

    @Column(name = "event_type", nullable = false, length = 64, updatable = false)
    private String eventType;

    @Column(name = "event_version", nullable = false, length = 16, updatable = false)
    private String eventVersion;

    @Column(name = "idempotency_key", nullable = false, length = 150, updatable = false)
    private String idempotencyKey;

    @Column(name = "payload_json", nullable = false, columnDefinition = "text", updatable = false)
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private WebhookDeliveryStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_attempted_at")
    private Instant lastAttemptedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "last_http_status")
    private Integer lastHttpStatus;

    @Column(name = "last_error_reason", length = 500)
    private String lastErrorReason;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WebhookDeliveryEntity() {
    }

    WebhookDeliveryEntity(
            UUID webhookEndpointId,
            UUID customerId,
            UUID sourceEventId,
            String eventType,
            String eventVersion,
            String idempotencyKey,
            String payloadJson,
            int maxAttempts,
            Instant now
    ) {
        this.webhookEndpointId = webhookEndpointId;
        this.customerId = customerId;
        this.sourceEventId = sourceEventId;
        this.eventType = eventType;
        this.eventVersion = eventVersion;
        this.idempotencyKey = idempotencyKey;
        this.payloadJson = payloadJson;
        this.status = WebhookDeliveryStatus.PENDING;
        this.attemptCount = 0;
        this.maxAttempts = maxAttempts;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    int nextAttemptNumber() {
        return this.attemptCount + 1;
    }

    void markDelivered(int httpStatus, Instant attemptTime) {
        this.attemptCount += 1;
        this.status = WebhookDeliveryStatus.DELIVERED;
        this.nextAttemptAt = null;
        this.lastAttemptedAt = attemptTime;
        if (this.deliveredAt == null) {
            this.deliveredAt = attemptTime;
        }
        this.lastHttpStatus = httpStatus;
        this.lastErrorReason = null;
        this.updatedAt = attemptTime;
    }

    void markScheduledForRetry(Integer httpStatus, String errorReason, Instant nextAttemptAt, Instant attemptTime) {
        this.attemptCount += 1;
        this.status = WebhookDeliveryStatus.RETRYING;
        this.nextAttemptAt = nextAttemptAt;
        this.lastAttemptedAt = attemptTime;
        this.lastHttpStatus = httpStatus;
        this.lastErrorReason = truncate(errorReason, 500);
        this.updatedAt = attemptTime;
    }

    void markExhausted(Integer httpStatus, String errorReason, Instant attemptTime) {
        this.attemptCount += 1;
        this.status = WebhookDeliveryStatus.EXHAUSTED;
        this.nextAttemptAt = null;
        this.lastAttemptedAt = attemptTime;
        this.lastHttpStatus = httpStatus;
        this.lastErrorReason = truncate(errorReason, 500);
        this.updatedAt = attemptTime;
    }

    void recordManualReplayFailure(Integer httpStatus, String errorReason, Instant attemptTime) {
        this.attemptCount += 1;
        this.lastAttemptedAt = attemptTime;
        this.lastHttpStatus = httpStatus;
        this.lastErrorReason = truncate(errorReason, 500);
        this.updatedAt = attemptTime;
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

    UUID getWebhookEndpointId() {
        return webhookEndpointId;
    }

    UUID getCustomerId() {
        return customerId;
    }

    UUID getSourceEventId() {
        return sourceEventId;
    }

    String getEventType() {
        return eventType;
    }

    String getEventVersion() {
        return eventVersion;
    }

    String getIdempotencyKey() {
        return idempotencyKey;
    }

    String getPayloadJson() {
        return payloadJson;
    }

    WebhookDeliveryStatus getStatus() {
        return status;
    }

    int getAttemptCount() {
        return attemptCount;
    }

    int getMaxAttempts() {
        return maxAttempts;
    }

    Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    Instant getLastAttemptedAt() {
        return lastAttemptedAt;
    }

    Instant getDeliveredAt() {
        return deliveredAt;
    }

    Integer getLastHttpStatus() {
        return lastHttpStatus;
    }

    String getLastErrorReason() {
        return lastErrorReason;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
