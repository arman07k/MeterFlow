package com.meterflow.billing.internal;

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
@Table(name = "tax_policies")
class TaxPolicyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "policy_code", nullable = false, length = 64, updatable = false)
    private String policyCode;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "jurisdiction", nullable = false, length = 80)
    private String jurisdiction;

    @Column(name = "tax_name", nullable = false, length = 100)
    private String taxName;

    @Column(name = "rate_percent", nullable = false, precision = 7, scale = 4)
    private BigDecimal ratePercent;

    @Column(name = "tax_exempt", nullable = false)
    private boolean taxExempt;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TaxPolicyEntity() {
    }

    TaxPolicyEntity(
            String policyCode,
            String name,
            UUID customerId,
            String jurisdiction,
            String taxName,
            BigDecimal ratePercent,
            boolean taxExempt,
            Instant now
    ) {
        this.policyCode = policyCode;
        this.name = name;
        this.customerId = customerId;
        this.jurisdiction = jurisdiction;
        this.taxName = taxName;
        this.ratePercent = ratePercent;
        this.taxExempt = taxExempt;
        this.active = true;
        this.createdAt = now;
        this.updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    String getPolicyCode() {
        return policyCode;
    }

    String getName() {
        return name;
    }

    UUID getCustomerId() {
        return customerId;
    }

    String getJurisdiction() {
        return jurisdiction;
    }

    String getTaxName() {
        return taxName;
    }

    BigDecimal getRatePercent() {
        return ratePercent;
    }

    boolean isTaxExempt() {
        return taxExempt;
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

    void deactivate(Instant now) {
        this.active = false;
        this.updatedAt = now;
    }
}
