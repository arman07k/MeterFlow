package com.meterflow.billing.internal;

import com.meterflow.billing.api.BillingRunStatus;
import com.meterflow.billing.api.BillingRunType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "billing_runs")
class BillingRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "batch_job_execution_id")
    private Long batchJobExecutionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "run_type", nullable = false, length = 24, updatable = false)
    private BillingRunType runType;

    @Column(name = "customer_id", updatable = false)
    private UUID customerId;

    @Column(name = "cutoff_timestamp", nullable = false, updatable = false)
    private Instant cutoffTimestamp;

    @Column(name = "idempotency_key", length = 150, updatable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private BillingRunStatus status;

    @Column(name = "processed_count", nullable = false)
    private int processedCount;

    @Column(name = "created_count", nullable = false)
    private int createdCount;

    @Column(name = "skipped_count", nullable = false)
    private int skippedCount;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    @Column(name = "error_summary", length = 500)
    private String errorSummary;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected BillingRunEntity() {
    }

    BillingRunEntity(
            BillingRunType runType,
            UUID customerId,
            Instant cutoffTimestamp,
            String idempotencyKey,
            Instant startedAt
    ) {
        this.runType = runType;
        this.customerId = customerId;
        this.cutoffTimestamp = cutoffTimestamp;
        this.idempotencyKey = idempotencyKey;
        this.status = BillingRunStatus.RUNNING;
        this.startedAt = startedAt;
    }

    UUID getId() {
        return id;
    }

    Long getBatchJobExecutionId() {
        return batchJobExecutionId;
    }

    BillingRunType getRunType() {
        return runType;
    }

    UUID getCustomerId() {
        return customerId;
    }

    Instant getCutoffTimestamp() {
        return cutoffTimestamp;
    }

    String getIdempotencyKey() {
        return idempotencyKey;
    }

    BillingRunStatus getStatus() {
        return status;
    }

    int getProcessedCount() {
        return processedCount;
    }

    int getCreatedCount() {
        return createdCount;
    }

    int getSkippedCount() {
        return skippedCount;
    }

    int getFailedCount() {
        return failedCount;
    }

    String getErrorSummary() {
        return errorSummary;
    }

    Instant getStartedAt() {
        return startedAt;
    }

    Instant getCompletedAt() {
        return completedAt;
    }

    void attachJobExecution(Long jobExecutionId) {
        this.batchJobExecutionId = jobExecutionId;
    }

    void markResuming() {
        this.status = BillingRunStatus.RUNNING;
        this.errorSummary = null;
        this.completedAt = null;
    }

    void complete(int processed, int created, int skipped, int failed, Instant completedAt) {
        this.processedCount = processed;
        this.createdCount = created;
        this.skippedCount = skipped;
        this.failedCount = failed;
        this.status = failed > 0 && created == 0 && skipped == 0 ? BillingRunStatus.FAILED : BillingRunStatus.COMPLETED;
        this.completedAt = completedAt;
    }

    void fail(String errorSummary, int processed, int created, int skipped, int failed, Instant completedAt) {
        this.processedCount = processed;
        this.createdCount = created;
        this.skippedCount = skipped;
        this.failedCount = failed;
        this.errorSummary = errorSummary == null ? null : errorSummary.substring(0, Math.min(errorSummary.length(), 500));
        this.status = BillingRunStatus.FAILED;
        this.completedAt = completedAt;
    }
}
