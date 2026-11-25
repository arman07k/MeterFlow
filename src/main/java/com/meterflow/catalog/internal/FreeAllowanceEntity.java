package com.meterflow.catalog.internal;

import com.meterflow.catalog.api.AllowanceResetPeriod;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "free_allowances")
class FreeAllowanceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_version_id", nullable = false, updatable = false)
    private PlanVersionEntity planVersion;

    @Column(name = "metric_id")
    private UUID metricId;

    @Column(name = "target_charge_id")
    private UUID targetChargeId;

    @Column(name = "free_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal freeQuantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "reset_period", nullable = false, length = 20)
    private AllowanceResetPeriod resetPeriod;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FreeAllowanceEntity() {
    }

    FreeAllowanceEntity(
            PlanVersionEntity planVersion,
            UUID metricId,
            UUID targetChargeId,
            BigDecimal freeQuantity,
            AllowanceResetPeriod resetPeriod,
            Instant now
    ) {
        this.planVersion = planVersion;
        this.metricId = metricId;
        this.targetChargeId = targetChargeId;
        this.freeQuantity = freeQuantity;
        this.resetPeriod = resetPeriod == null ? AllowanceResetPeriod.BILLING_PERIOD : resetPeriod;
        this.createdAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getPlanVersionId() {
        return planVersion.getId();
    }

    UUID getMetricId() {
        return metricId;
    }

    UUID getTargetChargeId() {
        return targetChargeId;
    }

    BigDecimal getFreeQuantity() {
        return freeQuantity;
    }

    AllowanceResetPeriod getResetPeriod() {
        return resetPeriod;
    }
}
