package com.meterflow.catalog.internal;

import com.meterflow.shared.SupportedCurrency;
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
@Table(name = "minimum_commitments")
class MinimumCommitmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_version_id", nullable = false, updatable = false)
    private PlanVersionEntity planVersion;

    @Column(name = "plan_charge_id")
    private UUID planChargeId;

    @Column(name = "minimum_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal minimumAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency_code", nullable = false, length = 3)
    private SupportedCurrency currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MinimumCommitmentEntity() {
    }

    MinimumCommitmentEntity(
            PlanVersionEntity planVersion,
            UUID planChargeId,
            BigDecimal minimumAmount,
            SupportedCurrency currency,
            Instant now
    ) {
        this.planVersion = planVersion;
        this.planChargeId = planChargeId;
        this.minimumAmount = minimumAmount;
        this.currency = currency;
        this.createdAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getPlanVersionId() {
        return planVersion.getId();
    }

    UUID getPlanChargeId() {
        return planChargeId;
    }

    BigDecimal getMinimumAmount() {
        return minimumAmount;
    }

    SupportedCurrency getCurrency() {
        return currency;
    }
}
