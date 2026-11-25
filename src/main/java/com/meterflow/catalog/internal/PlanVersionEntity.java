package com.meterflow.catalog.internal;

import com.meterflow.catalog.api.PlanVersionStatus;
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
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "plan_versions")
class PlanVersionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false, updatable = false)
    private PricingPlanEntity plan;

    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PlanVersionStatus status;

    @Column(name = "effective_from")
    private Instant effectiveFrom;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "cloned_from_version_id", updatable = false)
    private UUID clonedFromVersionId;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PlanVersionEntity() {
    }

    PlanVersionEntity(
            PricingPlanEntity plan,
            int versionNumber,
            Instant effectiveFrom,
            UUID clonedFromVersionId,
            Instant now
    ) {
        this.plan = plan;
        this.versionNumber = versionNumber;
        this.status = PlanVersionStatus.DRAFT;
        this.effectiveFrom = effectiveFrom;
        this.clonedFromVersionId = clonedFromVersionId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    PricingPlanEntity getPlan() {
        return plan;
    }

    int getVersionNumber() {
        return versionNumber;
    }

    PlanVersionStatus getStatus() {
        return status;
    }

    Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    Instant getPublishedAt() {
        return publishedAt;
    }

    UUID getClonedFromVersionId() {
        return clonedFromVersionId;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    void updateEffectiveFrom(Instant effectiveFrom, Instant now) {
        this.effectiveFrom = effectiveFrom;
        this.updatedAt = now;
    }

    void publish(Instant now) {
        this.status = PlanVersionStatus.PUBLISHED;
        this.publishedAt = now;
        if (this.effectiveFrom == null) {
            this.effectiveFrom = now;
        }
        this.updatedAt = now;
    }
}
