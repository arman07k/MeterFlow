package com.meterflow.catalog.internal;

import com.meterflow.catalog.api.MetricAggregationType;
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
@Table(name = "billable_metrics")
class BillableMetricEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "metric_key", nullable = false, length = 80)
    private String metricKey;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "aggregation_type", nullable = false, length = 20)
    private MetricAggregationType aggregationType;

    @Column(name = "target_property", length = 120)
    private String targetProperty;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BillableMetricEntity() {
    }

    BillableMetricEntity(
            String metricKey,
            String name,
            String description,
            MetricAggregationType aggregationType,
            String targetProperty,
            UUID customerId,
            Instant now
    ) {
        this.metricKey = metricKey;
        this.name = name;
        this.description = description;
        this.aggregationType = aggregationType;
        this.targetProperty = targetProperty;
        this.customerId = customerId;
        this.active = true;
        this.createdAt = now;
        this.updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    String getMetricKey() {
        return metricKey;
    }

    String getName() {
        return name;
    }

    String getDescription() {
        return description;
    }

    MetricAggregationType getAggregationType() {
        return aggregationType;
    }

    String getTargetProperty() {
        return targetProperty;
    }

    UUID getCustomerId() {
        return customerId;
    }

    boolean isActive() {
        return active;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    void update(String name, String description, String targetProperty, boolean active, Instant now) {
        this.name = name;
        this.description = description;
        this.targetProperty = targetProperty;
        this.active = active;
        this.updatedAt = now;
    }
}
