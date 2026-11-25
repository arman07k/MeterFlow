package com.meterflow.payments.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality Micrometer metrics for Payments & Dunning (FR8, QA5, ADR-015).
 * Never uses customerId, invoiceId, paymentId, or email as metric tags.
 */
@Component
class PaymentMetrics {

    private final Counter paymentsSucceededCounter;
    private final Counter paymentsFailedRetryableCounter;
    private final Counter paymentsFailedNonRetryableCounter;
    private final Counter retriesExecutedCounter;
    private final Counter retriesSucceededCounter;
    private final Counter retriesFailedCounter;
    private final Counter finalWarningsIssuedCounter;
    private final Counter subscriptionsSuspendedCounter;
    private final Counter subscriptionsReactivatedCounter;
    private final Counter workerRunsCounter;
    private final Counter workerErrorsCounter;
    private final Timer paymentProcessingTimer;
    private final Timer dunningWorkerTimer;

    PaymentMetrics(MeterRegistry registry) {
        this.paymentsSucceededCounter = Counter.builder("meterflow.payments.captured")
                .description("Total payments captured successfully")
                .register(registry);
        this.paymentsFailedRetryableCounter = Counter.builder("meterflow.payments.failed")
                .tag("retryable", "true")
                .description("Total retryable payment failures")
                .register(registry);
        this.paymentsFailedNonRetryableCounter = Counter.builder("meterflow.payments.failed")
                .tag("retryable", "false")
                .description("Total non-retryable payment failures")
                .register(registry);
        this.retriesExecutedCounter = Counter.builder("meterflow.dunning.retries.executed")
                .description("Total scheduled dunning retries executed")
                .register(registry);
        this.retriesSucceededCounter = Counter.builder("meterflow.dunning.retries.succeeded")
                .description("Total scheduled dunning retries that recovered payment")
                .register(registry);
        this.retriesFailedCounter = Counter.builder("meterflow.dunning.retries.failed")
                .description("Total scheduled dunning retries that failed")
                .register(registry);
        this.finalWarningsIssuedCounter = Counter.builder("meterflow.dunning.final_warnings")
                .description("Total dunning cases escalated to FINAL_WARNING")
                .register(registry);
        this.subscriptionsSuspendedCounter = Counter.builder("meterflow.dunning.subscriptions.suspended")
                .description("Total subscriptions suspended after dunning exhaustion")
                .register(registry);
        this.subscriptionsReactivatedCounter = Counter.builder("meterflow.dunning.subscriptions.reactivated")
                .description("Total PAST_DUE or SUSPENDED subscriptions reactivated after payment")
                .register(registry);
        this.workerRunsCounter = Counter.builder("meterflow.dunning.worker.runs")
                .description("Total dunning worker executions")
                .register(registry);
        this.workerErrorsCounter = Counter.builder("meterflow.dunning.worker.errors")
                .description("Total unexpected dunning worker per-case errors")
                .register(registry);
        this.paymentProcessingTimer = Timer.builder("meterflow.payments.processing.duration")
                .description("Payment processing transaction latency")
                .register(registry);
        this.dunningWorkerTimer = Timer.builder("meterflow.dunning.worker.duration")
                .description("Dunning worker pass execution duration")
                .register(registry);
    }

    void recordPaymentSucceeded(Duration duration) {
        paymentsSucceededCounter.increment();
        paymentProcessingTimer.record(duration);
    }

    void recordPaymentFailed(boolean retryable, Duration duration) {
        if (retryable) {
            paymentsFailedRetryableCounter.increment();
        } else {
            paymentsFailedNonRetryableCounter.increment();
        }
        paymentProcessingTimer.record(duration);
    }

    void recordRetryExecuted(boolean succeeded) {
        retriesExecutedCounter.increment();
        if (succeeded) {
            retriesSucceededCounter.increment();
        } else {
            retriesFailedCounter.increment();
        }
    }

    void recordFinalWarningIssued() {
        finalWarningsIssuedCounter.increment();
    }

    void recordSubscriptionSuspended() {
        subscriptionsSuspendedCounter.increment();
    }

    void recordSubscriptionReactivated() {
        subscriptionsReactivatedCounter.increment();
    }

    void recordWorkerRun(Duration duration) {
        workerRunsCounter.increment();
        dunningWorkerTimer.record(duration);
    }

    void recordWorkerError() {
        workerErrorsCounter.increment();
    }
}
