package com.meterflow.catalog.internal;

import com.meterflow.catalog.api.MetricFilterOperator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "metric_filters")
class MetricFilterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "metric_id", nullable = false, updatable = false)
    private BillableMetricEntity metric;

    @Column(name = "filter_order", nullable = false)
    private int filterOrder;

    @Column(name = "property_key", nullable = false, length = 120)
    private String propertyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "operator", nullable = false, length = 20)
    private MetricFilterOperator operator;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "filter_values", nullable = false, columnDefinition = "jsonb")
    private List<String> filterValues = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MetricFilterEntity() {
    }

    MetricFilterEntity(
            BillableMetricEntity metric,
            int filterOrder,
            String propertyKey,
            MetricFilterOperator operator,
            List<String> filterValues,
            Instant now
    ) {
        this.metric = metric;
        this.filterOrder = filterOrder;
        this.propertyKey = propertyKey;
        this.operator = operator;
        this.filterValues = new ArrayList<>(filterValues);
        this.createdAt = now;
    }

    UUID getMetricId() {
        return metric.getId();
    }

    int getFilterOrder() {
        return filterOrder;
    }

    String getPropertyKey() {
        return propertyKey;
    }

    MetricFilterOperator getOperator() {
        return operator;
    }

    List<String> getFilterValues() {
        return List.copyOf(filterValues);
    }
}
