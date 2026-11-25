package com.meterflow.payments.internal;

import com.meterflow.payments.api.DunningCaseStatus;
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
@Table(name = "dunning_cases")
class DunningCaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "subscription_id", nullable = false, updatable = false)
    private UUID subscriptionId;

    @Column(name = "dunning_policy_id", nullable = false, updatable = false)
    private UUID dunningPolicyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private DunningCaseStatus status;

    @Column(name = "initial_failure_at", nullable = false, updatable = false)
    private Instant initialFailureAt;

    @Column(name = "retries_completed", nullable = false)
    private int retriesCompleted;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "suspension_at")
    private Instant suspensionAt;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "recovered_at")
    private Instant recoveredAt;

    @Column(name = "last_failure_code", length = 64)
    private String lastFailureCode;

    @Column(name = "last_failure_reason", length = 255)
    private String lastFailureReason;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DunningCaseEntity() {
    }

    DunningCaseEntity(
            UUID invoiceId,
            UUID customerId,
            UUID subscriptionId,
            UUID dunningPolicyId,
            DunningCaseStatus initialStatus,
            Instant initialFailureAt,
            Instant nextRetryAt,
            Instant suspensionAt,
            String failureCode,
            String failureReason,
            Instant now
    ) {
        this.invoiceId = invoiceId;
        this.customerId = customerId;
        this.subscriptionId = subscriptionId;
        this.dunningPolicyId = dunningPolicyId;
        this.status = initialStatus;
        this.initialFailureAt = initialFailureAt;
        this.retriesCompleted = 0;
        this.nextRetryAt = nextRetryAt;
        this.suspensionAt = suspensionAt;
        this.lastFailureCode = failureCode;
        this.lastFailureReason = failureReason;
        this.createdAt = now;
        this.updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getInvoiceId() {
        return invoiceId;
    }

    UUID getCustomerId() {
        return customerId;
    }

    UUID getSubscriptionId() {
        return subscriptionId;
    }

    UUID getDunningPolicyId() {
        return dunningPolicyId;
    }

    DunningCaseStatus getStatus() {
        return status;
    }

    Instant getInitialFailureAt() {
        return initialFailureAt;
    }

    int getRetriesCompleted() {
        return retriesCompleted;
    }

    Instant getNextRetryAt() {
        return nextRetryAt;
    }

    Instant getSuspensionAt() {
        return suspensionAt;
    }

    Instant getSuspendedAt() {
        return suspendedAt;
    }

    Instant getRecoveredAt() {
        return recoveredAt;
    }

    String getLastFailureCode() {
        return lastFailureCode;
    }

    String getLastFailureReason() {
        return lastFailureReason;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    void recordRetryFailureAndScheduleNext(
            int completedAttemptNumber,
            Instant nextRetryAt,
            String failureCode,
            String failureReason,
            Instant now
    ) {
        if (completedAttemptNumber < 1 || completedAttemptNumber > 3) {
            throw new IllegalStateException("Retry attempt number must be between 1 and 3");
        }
        this.retriesCompleted = completedAttemptNumber;
        this.status = DunningCaseStatus.RETRY_SCHEDULED;
        this.nextRetryAt = nextRetryAt;
        this.lastFailureCode = failureCode;
        this.lastFailureReason = failureReason;
        this.updatedAt = now;
    }

    void escalateToFinalWarning(
            int completedRetries,
            Instant suspensionAt,
            String failureCode,
            String failureReason,
            Instant now
    ) {
        if (completedRetries < 0 || completedRetries > 3) {
            throw new IllegalStateException("Completed retries must be between 0 and 3");
        }
        this.retriesCompleted = completedRetries;
        this.status = DunningCaseStatus.FINAL_WARNING;
        this.nextRetryAt = null;
        this.suspensionAt = suspensionAt;
        this.lastFailureCode = failureCode;
        this.lastFailureReason = failureReason;
        this.updatedAt = now;
    }

    void markSuspended(Instant suspendedAt, Instant now) {
        this.status = DunningCaseStatus.SUSPENDED;
        this.nextRetryAt = null;
        this.suspendedAt = suspendedAt;
        this.updatedAt = now;
    }

    void markRecovered(Instant recoveredAt, Instant now) {
        this.status = DunningCaseStatus.RECOVERED;
        this.nextRetryAt = null;
        this.suspensionAt = null;
        this.recoveredAt = recoveredAt;
        this.updatedAt = now;
    }

    void markCancelled(Instant now) {
        this.status = DunningCaseStatus.CANCELLED;
        this.nextRetryAt = null;
        this.suspensionAt = null;
        this.updatedAt = now;
    }
}
