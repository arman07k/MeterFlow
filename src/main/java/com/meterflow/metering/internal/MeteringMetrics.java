package com.meterflow.metering.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality Micrometer operational metrics for the Metering Engine (QA5, ADR-013).
 * Never uses customerId, metricId, or idempotencyKey as metric tags.
 */
@Component
class MeteringMetrics {

    private final MeterRegistry registry;
    private final Counter acceptedCounter;
    private final Counter duplicateCounter;
    private final Counter lateCounter;
    private final Counter conflictCounter;
    private final Counter rollupFailuresCounter;
    private final Counter rollupReconciliationsCounter;
    private final DistributionSummary batchSizeSummary;
    private final DistributionSummary eventLagSecondsSummary;
    private final Timer ingestionTimer;
    private final Timer rollupTimer;
    private final Timer aggregationTimer;
    private final Timer queryTimer;

    MeteringMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.acceptedCounter = Counter.builder("meterflow.metering.events.accepted")
                .description("Total newly accepted usage events")
                .register(registry);
        this.duplicateCounter = Counter.builder("meterflow.metering.events.duplicate")
                .description("Total duplicate usage events safely deduplicated")
                .register(registry);
        this.lateCounter = Counter.builder("meterflow.metering.events.late")
                .description("Total late usage events accepted within the 72h window")
                .register(registry);
        this.conflictCounter = Counter.builder("meterflow.metering.events.conflict")
                .description("Total idempotency key payload conflicts rejected")
                .register(registry);
        this.rollupFailuresCounter = Counter.builder("meterflow.metering.rollup.failures")
                .description("Total rollup processing or reconciliation failures")
                .register(registry);
        this.rollupReconciliationsCounter = Counter.builder("meterflow.metering.rollup.reconciliations")
                .description("Total rollup reconciliation operations completed")
                .register(registry);
        this.batchSizeSummary = DistributionSummary.builder("meterflow.metering.batch.size")
                .description("Distribution of submitted usage ingestion batch sizes")
                .register(registry);
        this.eventLagSecondsSummary = DistributionSummary.builder("meterflow.metering.event.lag.seconds")
                .description("Elapsed seconds between event_timestamp and ingested_at")
                .register(registry);
        this.ingestionTimer = Timer.builder("meterflow.metering.ingestion.duration")
                .description("End-to-end batch ingestion execution duration")
                .register(registry);
        this.rollupTimer = Timer.builder("meterflow.metering.rollup.duration")
                .description("Transactional hourly rollup upsert duration")
                .register(registry);
        this.aggregationTimer = Timer.builder("meterflow.metering.aggregation.duration")
                .description("Metric usage aggregation computation duration")
                .register(registry);
        this.queryTimer = Timer.builder("meterflow.metering.query.duration")
                .description("Live usage estimate and query duration")
                .register(registry);
    }

    void recordBatchSize(int size) {
        batchSizeSummary.record(size);
    }

    void recordAccepted(int count) {
        if (count > 0) {
            acceptedCounter.increment(count);
        }
    }

    void recordLateAccepted(int count) {
        if (count > 0) {
            lateCounter.increment(count);
        }
    }

    void recordDuplicates(int count) {
        if (count > 0) {
            duplicateCounter.increment(count);
        }
    }

    void recordConflicts(int count) {
        if (count > 0) {
            conflictCounter.increment(count);
        }
    }

    void recordRejected(String lowCardinalityReason) {
        String safeTag = lowCardinalityReason == null || lowCardinalityReason.isBlank()
                ? "VALIDATION_ERROR"
                : lowCardinalityReason;
        Counter.builder("meterflow.metering.events.rejected")
                .description("Total rejected usage events by low-cardinality reason")
                .tag("reason", safeTag)
                .register(registry)
                .increment();
    }

    void recordEventLagSeconds(long lagSeconds) {
        if (lagSeconds >= 0) {
            eventLagSecondsSummary.record(lagSeconds);
        }
    }

    void recordIngestionDuration(Duration duration) {
        ingestionTimer.record(duration);
    }

    void recordRollupDuration(Duration duration) {
        rollupTimer.record(duration);
    }

    void recordAggregationDuration(Duration duration) {
        aggregationTimer.record(duration);
    }

    void recordQueryDuration(Duration duration) {
        queryTimer.record(duration);
    }

    void recordRollupFailure() {
        rollupFailuresCounter.increment();
    }

    void recordRollupReconciliation() {
        rollupReconciliationsCounter.increment();
    }

    void recordThresholdTriggered(int thresholdPercent) {
        Counter.builder("meterflow.metering.thresholds.triggered")
                .description("Total usage threshold events newly triggered")
                .tag("threshold_pct", String.valueOf(thresholdPercent))
                .register(registry)
                .increment();
    }
}
