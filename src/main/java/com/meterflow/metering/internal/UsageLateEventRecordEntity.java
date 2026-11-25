package com.meterflow.metering.internal;

import com.meterflow.metering.api.LateAdjustmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "usage_late_event_records")
class UsageLateEventRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "usage_event_id", nullable = false, updatable = false)
    private UUID usageEventId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "subscription_id", updatable = false)
    private UUID subscriptionId;

    @Column(name = "metric_id", nullable = false, updatable = false)
    private UUID metricId;

    @Column(name = "event_timestamp", nullable = false, updatable = false)
    private Instant eventTimestamp;

    @Column(name = "ingested_at", nullable = false, updatable = false)
    private Instant ingestedAt;

    @Column(name = "closed_period_start", updatable = false)
    private Instant closedPeriodStart;

    @Column(name = "closed_period_end", nullable = false, updatable = false)
    private Instant closedPeriodEnd;

    @Column(name = "hours_late", nullable = false, precision = 10, scale = 4, updatable = false)
    private BigDecimal hoursLate;

    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_status", nullable = false, length = 24)
    private LateAdjustmentStatus adjustmentStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected UsageLateEventRecordEntity() {
    }

    UUID getId() {
        return id;
    }

    UUID getUsageEventId() {
        return usageEventId;
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

    Instant getEventTimestamp() {
        return eventTimestamp;
    }

    Instant getIngestedAt() {
        return ingestedAt;
    }

    Instant getClosedPeriodStart() {
        return closedPeriodStart;
    }

    Instant getClosedPeriodEnd() {
        return closedPeriodEnd;
    }

    BigDecimal getHoursLate() {
        return hoursLate;
    }

    LateAdjustmentStatus getAdjustmentStatus() {
        return adjustmentStatus;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getConsumedAt() {
        return consumedAt;
    }
}
