package com.meterflow.metering.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "usage_rollups")
class UsageRollupEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "metric_id", nullable = false, updatable = false)
    private UUID metricId;

    @Column(name = "bucket_start", nullable = false, updatable = false)
    private Instant bucketStart;

    @Column(name = "bucket_end", nullable = false, updatable = false)
    private Instant bucketEnd;

    @Column(name = "event_count", nullable = false)
    private long eventCount;

    @Column(name = "sum_value", nullable = false, precision = 24, scale = 6)
    private BigDecimal sumValue;

    @Column(name = "max_value", precision = 19, scale = 6)
    private BigDecimal maxValue;

    @Column(name = "latest_value", precision = 19, scale = 6)
    private BigDecimal latestValue;

    @Column(name = "latest_event_timestamp")
    private Instant latestEventTimestamp;

    @Column(name = "latest_ingested_at")
    private Instant latestIngestedAt;

    @Column(name = "latest_event_id")
    private UUID latestEventId;

    @Column(name = "late_event_count", nullable = false)
    private long lateEventCount;

    @Column(name = "last_reconciled_at")
    private Instant lastReconciledAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UsageRollupEntity() {
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

    Instant getBucketStart() {
        return bucketStart;
    }

    Instant getBucketEnd() {
        return bucketEnd;
    }

    long getEventCount() {
        return eventCount;
    }

    BigDecimal getSumValue() {
        return sumValue;
    }

    BigDecimal getMaxValue() {
        return maxValue;
    }

    BigDecimal getLatestValue() {
        return latestValue;
    }

    Instant getLatestEventTimestamp() {
        return latestEventTimestamp;
    }

    Instant getLatestIngestedAt() {
        return latestIngestedAt;
    }

    UUID getLatestEventId() {
        return latestEventId;
    }

    long getLateEventCount() {
        return lateEventCount;
    }

    Instant getLastReconciledAt() {
        return lastReconciledAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
