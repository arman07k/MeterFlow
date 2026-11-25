package com.meterflow.catalog.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "charge_tiers")
class ChargeTierEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_charge_id", nullable = false, updatable = false)
    private PlanChargeEntity planCharge;

    @Column(name = "tier_order", nullable = false)
    private int tierOrder;

    @Column(name = "lower_bound", nullable = false, precision = 19, scale = 4)
    private BigDecimal lowerBound;

    @Column(name = "upper_bound", precision = 19, scale = 4)
    private BigDecimal upperBound;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 8)
    private BigDecimal unitPrice;

    @Column(name = "flat_fee", nullable = false, precision = 19, scale = 4)
    private BigDecimal flatFee;

    @Column(name = "description", length = 255)
    private String description;

    protected ChargeTierEntity() {
    }

    ChargeTierEntity(
            PlanChargeEntity planCharge,
            int tierOrder,
            BigDecimal lowerBound,
            BigDecimal upperBound,
            BigDecimal unitPrice,
            BigDecimal flatFee,
            String description
    ) {
        this.planCharge = planCharge;
        this.tierOrder = tierOrder;
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
        this.unitPrice = unitPrice;
        this.flatFee = flatFee == null ? BigDecimal.ZERO : flatFee;
        this.description = description;
    }

    UUID getPlanChargeId() {
        return planCharge.getId();
    }

    int getTierOrder() {
        return tierOrder;
    }

    BigDecimal getLowerBound() {
        return lowerBound;
    }

    BigDecimal getUpperBound() {
        return upperBound;
    }

    BigDecimal getUnitPrice() {
        return unitPrice;
    }

    BigDecimal getFlatFee() {
        return flatFee;
    }

    String getDescription() {
        return description;
    }
}
