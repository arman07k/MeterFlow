package com.meterflow.metering.internal;

import com.meterflow.metering.api.EventProcessingStatus;
import com.meterflow.metering.api.LateClassification;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "usage_events")
class UsageEventEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "subscription_id", updatable = false)
    private UUID subscriptionId;

    @Column(name = "metric_id", nullable = false, updatable = false)
    private UUID metricId;

    @Column(name = "idempotency_key", nullable = false, length = 150, updatable = false)
    private String idempotencyKey;

    @Column(name = "payload_hash", nullable = false, length = 64, updatable = false)
    private String payloadHash;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 6, updatable = false)
    private BigDecimal quantity;

    @Column(name = "measured_value", precision = 19, scale = 6, updatable = false)
    private BigDecimal measuredValue;

    @Column(name = "distinct_key", length = 255, updatable = false)
    private String distinctKey;

    @Column(name = "event_timestamp", nullable = false, updatable = false)
    private Instant eventTimestamp;

    @Column(name = "ingested_at", nullable = false, updatable = false)
    private Instant ingestedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "properties", nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> properties = new LinkedHashMap<>();

    @Column(name = "filter_matched", nullable = false, updatable = false)
    private boolean filterMatched;

    @Column(name = "late_event", nullable = false, updatable = false)
    private boolean lateEvent;

    @Enumerated(EnumType.STRING)
    @Column(name = "late_classification", nullable = false, length = 32, updatable = false)
    private LateClassification lateClassification;

    @Column(name = "billing_period_start", updatable = false)
    private Instant billingPeriodStart;

    @Column(name = "billing_period_end", updatable = false)
    private Instant billingPeriodEnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 24)
    private EventProcessingStatus processingStatus;

    protected UsageEventEntity() {
    }

    UUID getId() {
        return id;
    }

    UUID getCustomerId() {
        return customerId;
    }

    UUID getSubscriptionId() {
        return subscriptionId;
    }

    UUID getMetricId() {
        return metricId;
    }

    String getIdempotencyKey() {
        return idempotencyKey;
    }

    String getPayloadHash() {
        return payloadHash;
    }

    BigDecimal getQuantity() {
        return quantity;
    }

    BigDecimal getMeasuredValue() {
        return measuredValue;
    }

    String getDistinctKey() {
        return distinctKey;
    }

    Instant getEventTimestamp() {
        return eventTimestamp;
    }

    Instant getIngestedAt() {
        return ingestedAt;
    }

    Map<String, Object> getProperties() {
        return properties == null ? Map.of() : new LinkedHashMap<>(properties);
    }

    boolean isFilterMatched() {
        return filterMatched;
    }

    boolean isLateEvent() {
        return lateEvent;
    }

    LateClassification getLateClassification() {
        return lateClassification;
    }

    Instant getBillingPeriodStart() {
        return billingPeriodStart;
    }

    Instant getBillingPeriodEnd() {
        return billingPeriodEnd;
    }

    EventProcessingStatus getProcessingStatus() {
        return processingStatus;
    }
}
