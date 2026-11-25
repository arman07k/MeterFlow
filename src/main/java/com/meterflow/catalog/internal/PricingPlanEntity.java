package com.meterflow.catalog.internal;

import com.meterflow.shared.SupportedCurrency;
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
@Table(name = "pricing_plans")
class PricingPlanEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "plan_code", nullable = false, unique = true, length = 80)
    private String planCode;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency_code", nullable = false, length = 3)
    private SupportedCurrency currency;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PricingPlanEntity() {
    }

    PricingPlanEntity(
            String planCode,
            String name,
            String description,
            SupportedCurrency currency,
            Instant now
    ) {
        this.planCode = planCode;
        this.name = name;
        this.description = description;
        this.currency = currency;
        this.active = true;
        this.createdAt = now;
        this.updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    String getPlanCode() {
        return planCode;
    }

    String getName() {
        return name;
    }

    String getDescription() {
        return description;
    }

    SupportedCurrency getCurrency() {
        return currency;
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
}
