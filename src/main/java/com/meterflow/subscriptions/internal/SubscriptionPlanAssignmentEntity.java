package com.meterflow.subscriptions.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "subscription_plan_assignments")
class SubscriptionPlanAssignmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "subscription_id", nullable = false, updatable = false)
    private UUID subscriptionId;

    @Column(name = "plan_version_id", nullable = false, updatable = false)
    private UUID planVersionId;

    @Column(name = "seat_quantity", nullable = false, updatable = false)
    private int seatQuantity;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "assignment_reason", nullable = false, length = 40, updatable = false)
    private String assignmentReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SubscriptionPlanAssignmentEntity() {
    }

    SubscriptionPlanAssignmentEntity(
            UUID subscriptionId,
            UUID planVersionId,
            int seatQuantity,
            Instant effectiveFrom,
            Instant effectiveTo,
            String assignmentReason,
            Instant now
    ) {
        this.subscriptionId = subscriptionId;
        this.planVersionId = planVersionId;
        this.seatQuantity = seatQuantity;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.assignmentReason = assignmentReason;
        this.createdAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getSubscriptionId() {
        return subscriptionId;
    }

    UUID getPlanVersionId() {
        return planVersionId;
    }

    int getSeatQuantity() {
        return seatQuantity;
    }

    Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    Instant getEffectiveTo() {
        return effectiveTo;
    }

    String getAssignmentReason() {
        return assignmentReason;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    void closeAt(Instant effectiveTo) {
        this.effectiveTo = effectiveTo;
    }
}
