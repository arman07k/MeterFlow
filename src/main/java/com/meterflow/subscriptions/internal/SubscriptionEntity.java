package com.meterflow.subscriptions.internal;

import com.meterflow.shared.BillingInterval;
import com.meterflow.shared.SupportedCurrency;
import com.meterflow.subscriptions.api.SubscriptionStatus;
import com.meterflow.subscriptions.api.TrialConversionPolicy;
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
@Table(name = "subscriptions")
class SubscriptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "current_plan_version_id", nullable = false)
    private UUID currentPlanVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency_code", nullable = false, length = 3, updatable = false)
    private SupportedCurrency currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_interval", nullable = false, length = 20, updatable = false)
    private BillingInterval billingInterval;

    @Column(name = "billing_timezone", nullable = false, length = 64, updatable = false)
    private String billingTimezone;

    @Column(name = "seat_quantity", nullable = false)
    private int seatQuantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SubscriptionStatus status;

    @Column(name = "trial_start_at")
    private Instant trialStartAt;

    @Column(name = "trial_end_at")
    private Instant trialEndAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "trial_conversion_policy", nullable = false, length = 32)
    private TrialConversionPolicy trialConversionPolicy;

    @Column(name = "current_period_start")
    private Instant currentPeriodStart;

    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;

    @Column(name = "paused_at")
    private Instant pausedAt;

    @Column(name = "paused_remaining_seconds")
    private Long pausedRemainingSeconds;

    @Column(name = "cancel_at_period_end", nullable = false)
    private boolean cancelAtPeriodEnd;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "creation_idempotency_key", length = 150, updatable = false)
    private String creationIdempotencyKey;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SubscriptionEntity() {
    }

    SubscriptionEntity(
            UUID customerId,
            UUID planVersionId,
            SupportedCurrency currency,
            BillingInterval billingInterval,
            String billingTimezone,
            int seatQuantity,
            SubscriptionStatus status,
            Instant trialStartAt,
            Instant trialEndAt,
            TrialConversionPolicy trialConversionPolicy,
            Instant currentPeriodStart,
            Instant currentPeriodEnd,
            String creationIdempotencyKey,
            Instant now
    ) {
        this.customerId = customerId;
        this.currentPlanVersionId = planVersionId;
        this.currency = currency;
        this.billingInterval = billingInterval;
        this.billingTimezone = billingTimezone;
        this.seatQuantity = seatQuantity;
        this.status = status;
        this.trialStartAt = trialStartAt;
        this.trialEndAt = trialEndAt;
        this.trialConversionPolicy = trialConversionPolicy;
        this.currentPeriodStart = currentPeriodStart;
        this.currentPeriodEnd = currentPeriodEnd;
        this.cancelAtPeriodEnd = false;
        this.creationIdempotencyKey = creationIdempotencyKey;
        this.createdAt = now;
        this.updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getCustomerId() {
        return customerId;
    }

    UUID getCurrentPlanVersionId() {
        return currentPlanVersionId;
    }

    SupportedCurrency getCurrency() {
        return currency;
    }

    BillingInterval getBillingInterval() {
        return billingInterval;
    }

    String getBillingTimezone() {
        return billingTimezone;
    }

    int getSeatQuantity() {
        return seatQuantity;
    }

    SubscriptionStatus getStatus() {
        return status;
    }

    Instant getTrialStartAt() {
        return trialStartAt;
    }

    Instant getTrialEndAt() {
        return trialEndAt;
    }

    TrialConversionPolicy getTrialConversionPolicy() {
        return trialConversionPolicy;
    }

    Instant getCurrentPeriodStart() {
        return currentPeriodStart;
    }

    Instant getCurrentPeriodEnd() {
        return currentPeriodEnd;
    }

    Instant getPausedAt() {
        return pausedAt;
    }

    Long getPausedRemainingSeconds() {
        return pausedRemainingSeconds;
    }

    boolean isCancelAtPeriodEnd() {
        return cancelAtPeriodEnd;
    }

    Instant getCancelledAt() {
        return cancelledAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    void activateFromTrial(Instant periodStart, Instant periodEnd, Instant now) {
        this.status = SubscriptionStatus.ACTIVE;
        this.currentPeriodStart = periodStart;
        this.currentPeriodEnd = periodEnd;
        this.updatedAt = now;
    }

    void expireTrial(Instant now) {
        this.status = SubscriptionStatus.EXPIRED;
        this.updatedAt = now;
    }

    void applyPlanChange(UUID newPlanVersionId, int newSeatQuantity, Instant now) {
        this.currentPlanVersionId = newPlanVersionId;
        this.seatQuantity = newSeatQuantity;
        this.updatedAt = now;
    }

    void applyScheduledPlanChange(
            UUID newPlanVersionId,
            int newSeatQuantity,
            Instant newPeriodStart,
            Instant newPeriodEnd,
            Instant now
    ) {
        this.currentPlanVersionId = newPlanVersionId;
        this.seatQuantity = newSeatQuantity;
        this.currentPeriodStart = newPeriodStart;
        this.currentPeriodEnd = newPeriodEnd;
        this.updatedAt = now;
    }

    void pause(Instant pausedAt, long remainingSeconds, Instant now) {
        this.status = SubscriptionStatus.PAUSED;
        this.pausedAt = pausedAt;
        this.pausedRemainingSeconds = remainingSeconds;
        this.updatedAt = now;
    }

    void resume(Instant newPeriodStart, Instant newPeriodEnd, Instant now) {
        this.status = SubscriptionStatus.ACTIVE;
        this.currentPeriodStart = newPeriodStart;
        this.currentPeriodEnd = newPeriodEnd;
        this.pausedAt = null;
        this.pausedRemainingSeconds = null;
        this.updatedAt = now;
    }

    void scheduleCancelAtPeriodEnd(Instant now) {
        this.cancelAtPeriodEnd = true;
        this.updatedAt = now;
    }

    void cancelNow(Instant cancelledAt, Instant now) {
        this.status = SubscriptionStatus.CANCELLED;
        this.cancelAtPeriodEnd = false;
        this.cancelledAt = cancelledAt;
        this.pausedAt = null;
        this.pausedRemainingSeconds = null;
        this.updatedAt = now;
    }

    void markPastDue(Instant now) {
        this.status = SubscriptionStatus.PAST_DUE;
        this.updatedAt = now;
    }

    void suspendForNonPayment(Instant now) {
        this.status = SubscriptionStatus.SUSPENDED;
        this.updatedAt = now;
    }

    void reactivateToActive(Instant now) {
        this.status = SubscriptionStatus.ACTIVE;
        this.updatedAt = now;
    }
}
