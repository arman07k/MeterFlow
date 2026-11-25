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
@Table(name = "usage_threshold_evaluations")
class UsageThresholdEvaluationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "subscription_id", updatable = false)
    private UUID subscriptionId;

    @Column(name = "metric_id", updatable = false)
    private UUID metricId;

    @Column(name = "scope_key", nullable = false, length = 120, updatable = false)
    private String scopeKey;

    @Column(name = "window_start", nullable = false, updatable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false, updatable = false)
    private Instant windowEnd;

    @Column(name = "threshold_percent", nullable = false, updatable = false)
    private int thresholdPercent;

    @Column(name = "limit_value", nullable = false, precision = 19, scale = 6, updatable = false)
    private BigDecimal limitValue;

    @Column(name = "current_value", nullable = false, precision = 24, scale = 6, updatable = false)
    private BigDecimal currentValue;

    @Column(name = "utilization_percent", nullable = false, precision = 10, scale = 4, updatable = false)
    private BigDecimal utilizationPercent;

    @Column(name = "triggered_at", nullable = false, updatable = false)
    private Instant triggeredAt;

    protected UsageThresholdEvaluationEntity() {
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

    String getScopeKey() {
        return scopeKey;
    }

    Instant getWindowStart() {
        return windowStart;
    }

    Instant getWindowEnd() {
        return windowEnd;
    }

    int getThresholdPercent() {
        return thresholdPercent;
    }

    BigDecimal getLimitValue() {
        return limitValue;
    }

    BigDecimal getCurrentValue() {
        return currentValue;
    }

    BigDecimal getUtilizationPercent() {
        return utilizationPercent;
    }

    Instant getTriggeredAt() {
        return triggeredAt;
    }
}
