package com.meterflow.billing.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality Micrometer metrics for the Billing & Invoicing engine (QA5, ADR-014).
 * Never uses customerId, subscriptionId, or invoiceId as metric tags.
 */
@Component
class BillingMetrics {

    private final Counter draftsGeneratedCounter;
    private final Counter invoicesFinalizedCounter;
    private final Counter invoicesVoidedCounter;
    private final Counter creditsIssuedCounter;
    private final Counter creditsConsumedCounter;
    private final Counter creditNotesIssuedCounter;
    private final Counter adjustmentsCreatedCounter;
    private final Counter batchRunsCompletedCounter;
    private final Counter batchRunsFailedCounter;
    private final Timer draftGenerationTimer;
    private final Timer finalizationTimer;

    BillingMetrics(MeterRegistry registry) {
        this.draftsGeneratedCounter = Counter.builder("meterflow.billing.invoices.draft.generated")
                .description("Total draft invoices created or refreshed")
                .register(registry);
        this.invoicesFinalizedCounter = Counter.builder("meterflow.billing.invoices.finalized")
                .description("Total invoices locked as finalized")
                .register(registry);
        this.invoicesVoidedCounter = Counter.builder("meterflow.billing.invoices.voided")
                .description("Total invoices voided")
                .register(registry);
        this.creditsIssuedCounter = Counter.builder("meterflow.billing.credits.issued")
                .description("Total prepaid/promotional credit grants issued")
                .register(registry);
        this.creditsConsumedCounter = Counter.builder("meterflow.billing.credits.consumed")
                .description("Total credit consumption transactions recorded")
                .register(registry);
        this.creditNotesIssuedCounter = Counter.builder("meterflow.billing.credit_notes.issued")
                .description("Total immutable credit notes issued")
                .register(registry);
        this.adjustmentsCreatedCounter = Counter.builder("meterflow.billing.adjustments.created")
                .description("Total billing adjustments recorded (late usage and manual)")
                .register(registry);
        this.batchRunsCompletedCounter = Counter.builder("meterflow.billing.batch.runs.completed")
                .description("Total completed billing batch runs")
                .register(registry);
        this.batchRunsFailedCounter = Counter.builder("meterflow.billing.batch.runs.failed")
                .description("Total failed billing batch runs")
                .register(registry);
        this.draftGenerationTimer = Timer.builder("meterflow.billing.draft.duration")
                .description("Draft invoice calculation and persistence duration")
                .register(registry);
        this.finalizationTimer = Timer.builder("meterflow.billing.finalization.duration")
                .description("Invoice finalization and credit drawdown duration")
                .register(registry);
    }

    void recordDraftGenerated(Duration duration) {
        draftsGeneratedCounter.increment();
        draftGenerationTimer.record(duration);
    }

    void recordInvoiceFinalized(Duration duration) {
        invoicesFinalizedCounter.increment();
        finalizationTimer.record(duration);
    }

    void recordInvoiceVoided() {
        invoicesVoidedCounter.increment();
    }

    void recordCreditIssued() {
        creditsIssuedCounter.increment();
    }

    void recordCreditConsumed() {
        creditsConsumedCounter.increment();
    }

    void recordCreditNoteIssued() {
        creditNotesIssuedCounter.increment();
    }

    void recordAdjustmentCreated() {
        adjustmentsCreatedCounter.increment();
    }

    void recordBatchRunCompleted() {
        batchRunsCompletedCounter.increment();
    }

    void recordBatchRunFailed() {
        batchRunsFailedCounter.increment();
    }
}
