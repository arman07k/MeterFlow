package com.meterflow.payments.internal;

import com.meterflow.billing.api.InvoiceManagementFacade;
import com.meterflow.billing.api.InvoiceStatus;
import com.meterflow.billing.api.InvoiceSummary;
import com.meterflow.payments.api.DunningCaseStatus;
import com.meterflow.payments.api.DunningEventType;
import com.meterflow.payments.api.DunningExecutionSummary;
import com.meterflow.payments.api.DunningManagementFacade;
import com.meterflow.payments.api.MockPaymentOutcome;
import com.meterflow.payments.api.PaymentDetail;
import com.meterflow.payments.api.PaymentStatus;
import com.meterflow.payments.api.SubscriptionSuspendedForNonPaymentEvent;
import com.meterflow.subscriptions.api.SubscriptionManagementFacade;
import com.meterflow.subscriptions.api.SubscriptionQueryFacade;
import com.meterflow.subscriptions.api.SubscriptionStatus;
import com.meterflow.subscriptions.api.SubscriptionSummary;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Multi-instance safe, bounded, per-case transactional PostgreSQL {@code FOR UPDATE SKIP LOCKED}
 * dunning retry and suspension worker (FR8, ADR-007, ADR-015).
 *
 * <p>Executes due Day 1 / Day 3 / Day 7 retries separately from due {@code FINAL_WARNING -> SUSPENDED}
 * transitions (strictly zero 4th payment retry on suspension).
 */
@Service
class DunningWorkerService implements DunningManagementFacade {

    private static final Logger LOGGER = LoggerFactory.getLogger(DunningWorkerService.class);

    private final DunningCaseRepository dunningCaseRepository;
    private final DunningEventRepository dunningEventRepository;
    private final PaymentProcessingService paymentProcessingService;
    private final InvoiceManagementFacade invoiceManagementFacade;
    private final SubscriptionQueryFacade subscriptionQueryFacade;
    private final SubscriptionManagementFacade subscriptionManagementFacade;
    private final ApplicationEventPublisher eventPublisher;
    private final PaymentMetrics metrics;
    private final TransactionTemplate requiresNewTx;
    private final Clock clock;
    private final int batchSize;

    DunningWorkerService(
            DunningCaseRepository dunningCaseRepository,
            DunningEventRepository dunningEventRepository,
            PaymentProcessingService paymentProcessingService,
            InvoiceManagementFacade invoiceManagementFacade,
            SubscriptionQueryFacade subscriptionQueryFacade,
            SubscriptionManagementFacade subscriptionManagementFacade,
            ApplicationEventPublisher eventPublisher,
            PaymentMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${meterflow.payments.dunning.batch-size:50}") int batchSize
    ) {
        this.dunningCaseRepository = dunningCaseRepository;
        this.dunningEventRepository = dunningEventRepository;
        this.paymentProcessingService = paymentProcessingService;
        this.invoiceManagementFacade = invoiceManagementFacade;
        this.subscriptionQueryFacade = subscriptionQueryFacade;
        this.subscriptionManagementFacade = subscriptionManagementFacade;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.batchSize = Math.max(1, batchSize);
    }

    @Override
    public DunningExecutionSummary processDueDunning() {
        return processDueDunning(clock.instant().truncatedTo(ChronoUnit.MICROS), null);
    }

    @Override
    public DunningExecutionSummary processDueDunning(Instant referenceTime, MockPaymentOutcome retryOutcomeOverride) {
        long startNanos = System.nanoTime();
        Instant now = (referenceTime == null ? clock.instant() : referenceTime).truncatedTo(ChronoUnit.MICROS);

        int retriesProcessed = 0;
        int retriesSucceeded = 0;
        int retriesFailed = 0;
        int finalWarningsIssued = 0;
        int subscriptionsSuspended = 0;

        Set<UUID> skippedRetryCaseIds = new HashSet<>();
        for (int i = 0; i < batchSize; i++) {
            RetryStepResult stepResult = processSingleDueRetry(now, retryOutcomeOverride, skippedRetryCaseIds);
            if (stepResult.outcome() == StepOutcome.NONE_AVAILABLE) {
                break;
            }
            if (stepResult.outcome() == StepOutcome.ERROR && stepResult.caseId() != null) {
                skippedRetryCaseIds.add(stepResult.caseId());
                continue;
            }
            if (stepResult.outcome() == StepOutcome.SKIPPED_CLOSED) {
                continue;
            }
            retriesProcessed++;
            if (stepResult.outcome() == StepOutcome.SUCCEEDED) {
                retriesSucceeded++;
            } else if (stepResult.outcome() == StepOutcome.FAILED_RESCHEDULED) {
                retriesFailed++;
            } else if (stepResult.outcome() == StepOutcome.FAILED_FINAL_WARNING) {
                retriesFailed++;
                finalWarningsIssued++;
            }
        }

        Set<UUID> skippedSuspendCaseIds = new HashSet<>();
        for (int i = 0; i < batchSize; i++) {
            SuspendStepResult suspendResult = processSingleDueSuspension(now, skippedSuspendCaseIds);
            if (suspendResult.outcome() == SuspendOutcome.NONE_AVAILABLE) {
                break;
            }
            if (suspendResult.outcome() == SuspendOutcome.ERROR && suspendResult.caseId() != null) {
                skippedSuspendCaseIds.add(suspendResult.caseId());
                continue;
            }
            if (suspendResult.outcome() == SuspendOutcome.SUSPENDED) {
                subscriptionsSuspended++;
            }
        }

        metrics.recordWorkerRun(Duration.ofNanos(System.nanoTime() - startNanos));
        if (retriesProcessed > 0 || subscriptionsSuspended > 0) {
            LOGGER.info(
                    "Dunning worker pass complete: evaluatedAt={} retriesProcessed={} retriesSucceeded={} retriesFailed={} finalWarnings={} suspended={}",
                    now, retriesProcessed, retriesSucceeded, retriesFailed, finalWarningsIssued, subscriptionsSuspended);
        }

        return new DunningExecutionSummary(
                now,
                retriesProcessed,
                retriesSucceeded,
                retriesFailed,
                finalWarningsIssued,
                subscriptionsSuspended
        );
    }

    private RetryStepResult processSingleDueRetry(
            Instant now,
            MockPaymentOutcome outcomeOverride,
            Set<UUID> excludedIds
    ) {
        final UUID[] claimedId = new UUID[1];
        try {
            return requiresNewTx.execute(status -> {
                Optional<DunningCaseEntity> claimedOpt = excludedIds.isEmpty()
                        ? dunningCaseRepository.claimNextDueRetry(now)
                        : dunningCaseRepository.claimNextDueRetryExcluding(now, excludedIds);
                if (claimedOpt.isEmpty()) {
                    return new RetryStepResult(StepOutcome.NONE_AVAILABLE, null);
                }

                DunningCaseEntity dunningCase = claimedOpt.get();
                claimedId[0] = dunningCase.getId();

                // Guard against invoices already settled/voided or subscriptions already cancelled/expired.
                Optional<InvoiceSummary> invoiceOpt = invoiceManagementFacade.findInvoiceById(dunningCase.getInvoiceId());
                if (invoiceOpt.isEmpty()
                        || invoiceOpt.get().status() == InvoiceStatus.PAID
                        || invoiceOpt.get().status() == InvoiceStatus.VOID
                        || invoiceOpt.get().status() == InvoiceStatus.WRITTEN_OFF
                        || !invoiceOpt.get().amountDue().isPositive()) {
                    if (invoiceOpt.isPresent() && invoiceOpt.get().status() == InvoiceStatus.PAID) {
                        dunningCase.markRecovered(now, now);
                    } else {
                        dunningCase.markCancelled(now);
                    }
                    dunningCaseRepository.saveAndFlush(dunningCase);
                    return new RetryStepResult(StepOutcome.SKIPPED_CLOSED, dunningCase.getId());
                }

                int attemptNumber = dunningCase.getRetriesCompleted() + 1;
                if (attemptNumber > DunningScheduleCalculator.REQUIRED_RETRY_COUNT) {
                    return new RetryStepResult(StepOutcome.SKIPPED_CLOSED, dunningCase.getId());
                }

                PaymentDetail result = paymentProcessingService.executeDunningRetryAttempt(
                        dunningCase, attemptNumber, outcomeOverride, now);

                boolean succeeded = result.payment().status() == PaymentStatus.CAPTURED;
                metrics.recordRetryExecuted(succeeded);
                if (succeeded) {
                    return new RetryStepResult(StepOutcome.SUCCEEDED, dunningCase.getId());
                }

                DunningCaseEntity reloaded = dunningCaseRepository.findById(dunningCase.getId()).orElse(dunningCase);
                if (reloaded.getStatus() == DunningCaseStatus.FINAL_WARNING) {
                    return new RetryStepResult(StepOutcome.FAILED_FINAL_WARNING, dunningCase.getId());
                }
                return new RetryStepResult(StepOutcome.FAILED_RESCHEDULED, dunningCase.getId());
            });
        } catch (Exception ex) {
            metrics.recordWorkerError();
            LOGGER.error("Unexpected error executing due dunning retry: caseId={} reason={}",
                    claimedId[0], ex.getMessage());
            return new RetryStepResult(StepOutcome.ERROR, claimedId[0]);
        }
    }

    private SuspendStepResult processSingleDueSuspension(Instant now, Set<UUID> excludedIds) {
        final UUID[] claimedId = new UUID[1];
        try {
            return requiresNewTx.execute(status -> {
                Optional<DunningCaseEntity> claimedOpt = excludedIds.isEmpty()
                        ? dunningCaseRepository.claimNextDueSuspension(now)
                        : dunningCaseRepository.claimNextDueSuspensionExcluding(now, excludedIds);
                if (claimedOpt.isEmpty()) {
                    return new SuspendStepResult(SuspendOutcome.NONE_AVAILABLE, null);
                }

                DunningCaseEntity dunningCase = claimedOpt.get();
                claimedId[0] = dunningCase.getId();

                Optional<InvoiceSummary> invoiceOpt = invoiceManagementFacade.findInvoiceById(dunningCase.getInvoiceId());
                if (invoiceOpt.isPresent() && invoiceOpt.get().status() == InvoiceStatus.PAID) {
                    dunningCase.markRecovered(now, now);
                    dunningCaseRepository.saveAndFlush(dunningCase);
                    return new SuspendStepResult(SuspendOutcome.SKIPPED_CLOSED, dunningCase.getId());
                }
                if (invoiceOpt.isEmpty()
                        || invoiceOpt.get().status() == InvoiceStatus.VOID
                        || invoiceOpt.get().status() == InvoiceStatus.WRITTEN_OFF) {
                    dunningCase.markCancelled(now);
                    dunningCaseRepository.saveAndFlush(dunningCase);
                    return new SuspendStepResult(SuspendOutcome.SKIPPED_CLOSED, dunningCase.getId());
                }

                // Separate suspension timer executes ZERO payment attempts (FR8, ADR-007).
                String prevStatus = dunningCase.getStatus().name();
                dunningCase.markSuspended(now, now);
                dunningCaseRepository.saveAndFlush(dunningCase);

                Optional<SubscriptionSummary> subOpt =
                        subscriptionQueryFacade.findSubscriptionById(dunningCase.getSubscriptionId());
                boolean suspendedSubscription = false;
                if (subOpt.isPresent() && !subOpt.get().status().isTerminal()) {
                    SubscriptionSummary afterSub = subscriptionManagementFacade.suspendForNonPayment(
                            dunningCase.getSubscriptionId(),
                            "DUNNING_EXHAUSTED_INVOICE_" + dunningCase.getInvoiceId(),
                            "dunning-suspend:" + dunningCase.getId()
                    );
                    suspendedSubscription = afterSub.status() == SubscriptionStatus.SUSPENDED;
                }

                dunningEventRepository.save(new DunningEventEntity(
                        dunningCase.getId(),
                        dunningCase.getInvoiceId(),
                        dunningCase.getCustomerId(),
                        dunningCase.getSubscriptionId(),
                        DunningEventType.SUBSCRIPTION_SUSPENDED,
                        prevStatus,
                        DunningCaseStatus.SUSPENDED.name(),
                        dunningCase.getRetriesCompleted(),
                        null,
                        Map.of("subscriptionSuspended", suspendedSubscription, "zeroFourthRetry", true),
                        now
                ));

                if (suspendedSubscription) {
                    metrics.recordSubscriptionSuspended();
                    eventPublisher.publishEvent(new SubscriptionSuspendedForNonPaymentEvent(
                            dunningCase.getId(),
                            dunningCase.getInvoiceId(),
                            dunningCase.getCustomerId(),
                            dunningCase.getSubscriptionId(),
                            dunningCase.getRetriesCompleted(),
                            "Dunning policy exhausted without payment",
                            now
                    ));
                }

                return new SuspendStepResult(SuspendOutcome.SUSPENDED, dunningCase.getId());
            });
        } catch (Exception ex) {
            metrics.recordWorkerError();
            LOGGER.error("Unexpected error executing due dunning suspension: caseId={} reason={}",
                    claimedId[0], ex.getMessage());
            return new SuspendStepResult(SuspendOutcome.ERROR, claimedId[0]);
        }
    }

    private enum StepOutcome {
        NONE_AVAILABLE,
        SKIPPED_CLOSED,
        SUCCEEDED,
        FAILED_RESCHEDULED,
        FAILED_FINAL_WARNING,
        ERROR
    }

    private record RetryStepResult(StepOutcome outcome, UUID caseId) {
    }

    private enum SuspendOutcome {
        NONE_AVAILABLE,
        SKIPPED_CLOSED,
        SUSPENDED,
        ERROR
    }

    private record SuspendStepResult(SuspendOutcome outcome, UUID caseId) {
    }
}
