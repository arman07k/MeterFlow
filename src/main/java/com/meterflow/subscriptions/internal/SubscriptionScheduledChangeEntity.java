package com.meterflow.subscriptions.internal;

import com.meterflow.subscriptions.api.PlanChangeDirection;
import com.meterflow.subscriptions.api.ScheduledChangeStatus;
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
@Table(name = "subscription_scheduled_changes")
class SubscriptionScheduledChangeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "subscription_id", nullable = false, updatable = false)
    private UUID subscriptionId;

    @Column(name = "current_plan_version_id", nullable = false, updatable = false)
    private UUID currentPlanVersionId;

    @Column(name = "target_plan_version_id", nullable = false, updatable = false)
    private UUID targetPlanVersionId;

    @Column(name = "target_seat_quantity", nullable = false, updatable = false)
    private int targetSeatQuantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_direction", nullable = false, length = 24, updatable = false)
    private PlanChangeDirection changeDirection;

    @Column(name = "effective_at", nullable = false, updatable = false)
    private Instant effectiveAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ScheduledChangeStatus status;

    @Column(name = "idempotency_key", length = 150, updatable = false)
    private String idempotencyKey;

    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SubscriptionScheduledChangeEntity() {
    }

    SubscriptionScheduledChangeEntity(
            UUID subscriptionId,
            UUID currentPlanVersionId,
            UUID targetPlanVersionId,
            int targetSeatQuantity,
            PlanChangeDirection changeDirection,
            Instant effectiveAt,
            String idempotencyKey,
            Instant now
    ) {
        this.subscriptionId = subscriptionId;
        this.currentPlanVersionId = currentPlanVersionId;
        this.targetPlanVersionId = targetPlanVersionId;
        this.targetSeatQuantity = targetSeatQuantity;
        this.changeDirection = changeDirection;
        this.effectiveAt = effectiveAt;
        this.status = ScheduledChangeStatus.PENDING;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = now;
        this.updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getSubscriptionId() {
        return subscriptionId;
    }

    UUID getCurrentPlanVersionId() {
        return currentPlanVersionId;
    }

    UUID getTargetPlanVersionId() {
        return targetPlanVersionId;
    }

    int getTargetSeatQuantity() {
        return targetSeatQuantity;
    }

    PlanChangeDirection getChangeDirection() {
        return changeDirection;
    }

    Instant getEffectiveAt() {
        return effectiveAt;
    }

    ScheduledChangeStatus getStatus() {
        return status;
    }

    String getIdempotencyKey() {
        return idempotencyKey;
    }

    Instant getAppliedAt() {
        return appliedAt;
    }

    Instant getCancelledAt() {
        return cancelledAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    void markApplied(Instant now) {
        this.status = ScheduledChangeStatus.APPLIED;
        this.appliedAt = now;
        this.updatedAt = now;
    }

    void markCancelled(Instant now) {
        this.status = ScheduledChangeStatus.CANCELLED;
        this.cancelledAt = now;
        this.updatedAt = now;
    }

    void markSuperseded(Instant now) {
        this.status = ScheduledChangeStatus.SUPERSEDED;
        this.cancelledAt = now;
        this.updatedAt = now;
    }
}
