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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "plan_charges")
class PlanChargeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_version_id", nullable = false, updatable = false)
    private PlanVersionEntity planVersion;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "charge_code", nullable = false, length = 80)
    private String chargeCode;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "charge_type", nullable = false, length = 40)
    private String chargeType;

    @Column(name = "metric_id")
    private UUID metricId;

    @Column(name = "flat_amount", precision = 19, scale = 4)
    private BigDecimal flatAmount;

    @Column(name = "unit_price", precision = 19, scale = 8)
    private BigDecimal unitPrice;

    @Column(name = "package_size", precision = 19, scale = 4)
    private BigDecimal packageSize;

    @Column(name = "package_price", precision = 19, scale = 4)
    private BigDecimal packagePrice;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "charge_config", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> chargeConfig = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PlanChargeEntity() {
    }

    PlanChargeEntity(
            PlanVersionEntity planVersion,
            int displayOrder,
            String chargeCode,
            String description,
            String chargeType,
            UUID metricId,
            BigDecimal flatAmount,
            BigDecimal unitPrice,
            BigDecimal packageSize,
            BigDecimal packagePrice,
            Map<String, Object> chargeConfig,
            Instant now
    ) {
        this.planVersion = planVersion;
        this.displayOrder = displayOrder;
        this.chargeCode = chargeCode;
        this.description = description;
        this.chargeType = chargeType;
        this.metricId = metricId;
        this.flatAmount = flatAmount;
        this.unitPrice = unitPrice;
        this.packageSize = packageSize;
        this.packagePrice = packagePrice;
        this.chargeConfig = chargeConfig == null ? new LinkedHashMap<>() : new LinkedHashMap<>(chargeConfig);
        this.createdAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getPlanVersionId() {
        return planVersion.getId();
    }

    int getDisplayOrder() {
        return displayOrder;
    }

    String getChargeCode() {
        return chargeCode;
    }

    String getDescription() {
        return description;
    }

    String getChargeType() {
        return chargeType;
    }

    UUID getMetricId() {
        return metricId;
    }

    BigDecimal getFlatAmount() {
        return flatAmount;
    }

    BigDecimal getUnitPrice() {
        return unitPrice;
    }

    BigDecimal getPackageSize() {
        return packageSize;
    }

    BigDecimal getPackagePrice() {
        return packagePrice;
    }

    Map<String, Object> getChargeConfig() {
        return chargeConfig == null ? Map.of() : new LinkedHashMap<>(chargeConfig);
    }
}
