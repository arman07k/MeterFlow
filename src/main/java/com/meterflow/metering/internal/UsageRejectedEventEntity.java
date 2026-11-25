package com.meterflow.metering.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "usage_rejected_events")
class UsageRejectedEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "customer_id", updatable = false)
    private UUID customerId;

    @Column(name = "metric_id", updatable = false)
    private UUID metricId;

    @Column(name = "idempotency_key", length = 150, updatable = false)
    private String idempotencyKey;

    @Column(name = "rejection_code", nullable = false, length = 48, updatable = false)
    private String rejectionCode;

    @Column(name = "rejection_reason", nullable = false, length = 500, updatable = false)
    private String rejectionReason;

    @Column(name = "event_timestamp", updatable = false)
    private Instant eventTimestamp;

    @Column(name = "payload_hash", length = 64, updatable = false)
    private String payloadHash;

    @Column(name = "rejected_at", nullable = false, updatable = false)
    private Instant rejectedAt;

    protected UsageRejectedEventEntity() {
    }

    UUID getId() {
        return id;
    }

    UUID getCustomerId() {
        return customerId;
    }

    UUID getMetricId() {
        return metricId;
    }

    String getIdempotencyKey() {
        return idempotencyKey;
    }

    String getRejectionCode() {
        return rejectionCode;
    }

    String getRejectionReason() {
        return rejectionReason;
    }

    Instant getEventTimestamp() {
        return eventTimestamp;
    }

    String getPayloadHash() {
        return payloadHash;
    }

    Instant getRejectedAt() {
        return rejectedAt;
    }
}
