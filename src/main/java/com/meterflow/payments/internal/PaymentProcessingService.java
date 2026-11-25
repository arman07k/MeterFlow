package com.meterflow.payments.internal;

import com.meterflow.billing.api.InvoiceManagementFacade;
import com.meterflow.billing.api.InvoiceStatus;
import com.meterflow.billing.api.InvoiceSummary;
import com.meterflow.payments.api.DunningCaseStatus;
import com.meterflow.payments.api.DunningEventType;
import com.meterflow.payments.api.DuplicatePaymentSettlementException;
import com.meterflow.payments.api.IneligibleInvoiceForPaymentException;
import com.meterflow.payments.api.InitiatePaymentCommand;
import com.meterflow.payments.api.InvalidPaymentRequestException;
import com.meterflow.payments.api.MockPaymentOutcome;
import com.meterflow.payments.api.PaymentAttemptSource;
import com.meterflow.payments.api.PaymentAttemptStatus;
import com.meterflow.payments.api.PaymentDetail;
import com.meterflow.payments.api.PaymentFailedEvent;
import com.meterflow.payments.api.PaymentOverpaymentException;
import com.meterflow.payments.api.PaymentProcessingFacade;
import com.meterflow.payments.api.PaymentResourceNotFoundException;
import com.meterflow.payments.api.PaymentRetryScheduledEvent;
import com.meterflow.payments.api.PaymentStatus;
import com.meterflow.payments.api.PaymentSucceededEvent;
import com.meterflow.payments.api.SubscriptionReactivatedAfterPaymentEvent;
import com.meterflow.shared.CurrencyMismatchException;
import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import com.meterflow.subscriptions.api.SubscriptionManagementFacade;
import com.meterflow.subscriptions.api.SubscriptionQueryFacade;
import com.meterflow.subscriptions.api.SubscriptionStatus;
import com.meterflow.subscriptions.api.SubscriptionSummary;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authoritative mock payment processing and ACID settlement orchestrator (FR8, ADR-007, ADR-015).
 */
@Service
class PaymentProcessingService implements PaymentProcessingFacade {

    private static final Logger LOGGER = LoggerFactory.getLogger(PaymentProcessingService.class);

    private final PaymentTransactionRepository transactionRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final DunningCaseRepository dunningCaseRepository;
    private final DunningEventRepository dunningEventRepository;
    private final DunningPolicyRepository dunningPolicyRepository;
    private final DunningPolicyService dunningPolicyService;
    private final MockPaymentGateway mockPaymentGateway;
    private final InvoiceManagementFacade invoiceManagementFacade;
    private final SubscriptionQueryFacade subscriptionQueryFacade;
    private final SubscriptionManagementFacade subscriptionManagementFacade;
    private final ApplicationEventPublisher eventPublisher;
    private final PaymentMetrics metrics;
    private final Clock clock;

    PaymentProcessingService(
            PaymentTransactionRepository transactionRepository,
            PaymentAttemptRepository attemptRepository,
            DunningCaseRepository dunningCaseRepository,
            DunningEventRepository dunningEventRepository,
            DunningPolicyRepository dunningPolicyRepository,
            DunningPolicyService dunningPolicyService,
            MockPaymentGateway mockPaymentGateway,
            InvoiceManagementFacade invoiceManagementFacade,
            SubscriptionQueryFacade subscriptionQueryFacade,
            SubscriptionManagementFacade subscriptionManagementFacade,
            ApplicationEventPublisher eventPublisher,
            PaymentMetrics metrics,
            Clock clock
    ) {
        this.transactionRepository = transactionRepository;
        this.attemptRepository = attemptRepository;
        this.dunningCaseRepository = dunningCaseRepository;
        this.dunningEventRepository = dunningEventRepository;
        this.dunningPolicyRepository = dunningPolicyRepository;
        this.dunningPolicyService = dunningPolicyService;
        this.mockPaymentGateway = mockPaymentGateway;
        this.invoiceManagementFacade = invoiceManagementFacade;
        this.subscriptionQueryFacade = subscriptionQueryFacade;
        this.subscriptionManagementFacade = subscriptionManagementFacade;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    @Transactional
    public PaymentDetail initiatePayment(InitiatePaymentCommand command) {
        if (command == null || command.invoiceId() == null) {
            throw new InvalidPaymentRequestException("invoiceId must not be null");
        }
        PaymentAttemptSource source = command.attemptSource() == null
                ? PaymentAttemptSource.MANUAL
                : command.attemptSource();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return executeAttempt(
                command.invoiceId(),
                command.amount(),
                command.currency(),
                command.simulationOutcome(),
                source,
                0,
                command.idempotencyKey(),
                now
        );
    }

    @Transactional
    PaymentDetail executeDunningRetryAttempt(
            DunningCaseEntity dunningCase,
            int attemptNumber,
            MockPaymentOutcome outcomeOverride,
            Instant referenceTime
    ) {
        String idempotencyKey = "dunning-retry:" + dunningCase.getId() + ":attempt-" + attemptNumber;
        return executeAttempt(
                dunningCase.getInvoiceId(),
                null,
                null,
                outcomeOverride,
                PaymentAttemptSource.DUNNING_RETRY,
                attemptNumber,
                idempotencyKey,
                referenceTime.truncatedTo(ChronoUnit.MICROS)
        );
    }

    private PaymentDetail executeAttempt(
            UUID invoiceId,
            BigDecimal requestedAmount,
            SupportedCurrency requestedCurrency,
            MockPaymentOutcome requestedOutcome,
            PaymentAttemptSource attemptSource,
            int explicitAttemptNumber,
            String rawIdempotencyKey,
            Instant now
    ) {
        long startNanos = System.nanoTime();
        // Serialize all concurrent payment attempts targeting the same invoice at the DB level.
        transactionRepository.lockInvoiceForPayment(invoiceId);

        InvoiceSummary invoice = invoiceManagementFacade.findInvoiceById(invoiceId)
                .orElseThrow(() -> new PaymentResourceNotFoundException("Invoice not found: " + invoiceId));

        String idempotencyKey = normalizeOptionalKey(rawIdempotencyKey);
        if (idempotencyKey != null) {
            Optional<PaymentTransactionEntity> replay = transactionRepository
                    .findByCustomerIdAndIdempotencyKey(invoice.customerId(), idempotencyKey);
            if (replay.isPresent()) {
                return loadPaymentDetail(replay.get());
            }
        }

        if (invoice.status() == InvoiceStatus.PAID
                || transactionRepository.existsByInvoiceIdAndStatus(invoiceId, PaymentStatus.CAPTURED)) {
            throw new DuplicatePaymentSettlementException(
                    "Invoice " + invoiceId + " has already been settled");
        }

        if (invoice.status() != InvoiceStatus.FINALIZED && invoice.status() != InvoiceStatus.PAST_DUE) {
            throw new IneligibleInvoiceForPaymentException(
                    "Invoice " + invoiceId + " is not eligible for payment in status " + invoice.status());
        }

        Money currentDue = invoice.amountDue().roundToPersisted();
        if (!currentDue.isPositive()) {
            throw new IneligibleInvoiceForPaymentException(
                    "Invoice " + invoiceId + " has zero remaining balance due");
        }

        SupportedCurrency currency = requestedCurrency == null ? invoice.currency() : requestedCurrency;
        if (currency != invoice.currency()) {
            throw new CurrencyMismatchException(invoice.currency(), currency);
        }

        Money paymentAmount = requestedAmount == null
                ? currentDue
                : Money.of(requestedAmount, currency).roundToPersisted();
        if (!paymentAmount.isPositive()) {
            throw new InvalidPaymentRequestException("Payment amount must be positive");
        }
        if (paymentAmount.isGreaterThan(currentDue)) {
            throw new PaymentOverpaymentException(
                    "Payment amount " + paymentAmount + " exceeds remaining invoice balance due " + currentDue);
        }

        MockPaymentOutcome outcome = mockPaymentGateway.resolveOutcome(requestedOutcome);
        String paymentRef = "PAY-" + Year.now(ZoneOffset.UTC).getValue() + "-"
                + transactionRepository.nextPaymentReferenceSequence();

        Optional<DunningCaseEntity> existingCaseOpt = dunningCaseRepository.findByInvoiceIdForUpdate(invoiceId);
        int effectiveAttemptNumber = attemptSource == PaymentAttemptSource.DUNNING_RETRY
                ? explicitAttemptNumber
                : (existingCaseOpt.map(DunningCaseEntity::getRetriesCompleted).orElse(0));

        if (outcome.isSuccess()) {
            return handleSuccessfulPayment(
                    invoice,
                    existingCaseOpt.orElse(null),
                    paymentRef,
                    paymentAmount,
                    attemptSource,
                    effectiveAttemptNumber,
                    outcome,
                    idempotencyKey,
                    now,
                    startNanos
            );
        }
        return handleFailedPayment(
                invoice,
                existingCaseOpt.orElse(null),
                paymentRef,
                paymentAmount,
                attemptSource,
                effectiveAttemptNumber,
                outcome,
                idempotencyKey,
                now,
                startNanos
        );
    }

    private PaymentDetail handleSuccessfulPayment(
            InvoiceSummary invoice,
            DunningCaseEntity existingCase,
            String paymentRef,
            Money paymentAmount,
            PaymentAttemptSource attemptSource,
            int attemptNumber,
            MockPaymentOutcome outcome,
            String idempotencyKey,
            Instant now,
            long startNanos
    ) {
        InvoiceSummary updatedInvoice = invoiceManagementFacade.recordPaymentSettlement(
                invoice.id(), paymentAmount, paymentRef, idempotencyKey);

        boolean recoveredFromDunning = false;
        UUID dunningCaseId = existingCase == null ? null : existingCase.getId();

        if (existingCase != null && existingCase.getStatus().isOpen()
                && updatedInvoice.status() == InvoiceStatus.PAID) {
            String previousCaseStatus = existingCase.getStatus().name();
            existingCase.markRecovered(now, now);
            dunningCaseRepository.saveAndFlush(existingCase);
            recoveredFromDunning = true;

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("paymentReference", paymentRef);
            details.put("amount", paymentAmount.amountAsPlainString());
            details.put("attemptSource", attemptSource.name());
            dunningEventRepository.save(new DunningEventEntity(
                    existingCase.getId(),
                    invoice.id(),
                    invoice.customerId(),
                    invoice.subscriptionId(),
                    DunningEventType.RECOVERED,
                    previousCaseStatus,
                    DunningCaseStatus.RECOVERED.name(),
                    attemptNumber,
                    null,
                    details,
                    now
            ));
        }

        PaymentTransactionEntity savedTx = transactionRepository.saveAndFlush(new PaymentTransactionEntity(
                paymentRef,
                invoice.id(),
                invoice.customerId(),
                invoice.subscriptionId(),
                dunningCaseId,
                invoice.currency(),
                paymentAmount.amount(),
                PaymentStatus.CAPTURED,
                attemptSource,
                attemptNumber,
                outcome,
                false,
                null,
                null,
                idempotencyKey,
                now,
                now
        ));

        PaymentAttemptEntity savedAttempt = attemptRepository.saveAndFlush(new PaymentAttemptEntity(
                savedTx.getId(),
                invoice.id(),
                invoice.customerId(),
                dunningCaseId,
                attemptNumber,
                attemptSource,
                outcome,
                PaymentAttemptStatus.SUCCEEDED,
                false,
                null,
                null,
                paymentAmount.amount(),
                invoice.currency(),
                idempotencyKey,
                now
        ));

        // Reactivate eligible PAST_DUE / SUSPENDED subscriptions on full invoice settlement
        // while strictly protecting CANCELLED, EXPIRED, and manually PAUSED subscriptions.
        if (updatedInvoice.status() == InvoiceStatus.PAID) {
            Optional<SubscriptionSummary> subBeforeOpt =
                    subscriptionQueryFacade.findSubscriptionById(invoice.subscriptionId());
            if (subBeforeOpt.isPresent()) {
                SubscriptionStatus prevSubStatus = subBeforeOpt.get().status();
                if (prevSubStatus == SubscriptionStatus.PAST_DUE || prevSubStatus == SubscriptionStatus.SUSPENDED) {
                    SubscriptionSummary afterSub = subscriptionManagementFacade.reactivateAfterPayment(
                            invoice.subscriptionId(),
                            "PAYMENT_CAPTURED_" + paymentRef,
                            "pay-reactivate:" + savedTx.getId()
                    );
                    if (afterSub.status() == SubscriptionStatus.ACTIVE) {
                        metrics.recordSubscriptionReactivated();
                        eventPublisher.publishEvent(new SubscriptionReactivatedAfterPaymentEvent(
                                savedTx.getId(),
                                dunningCaseId,
                                invoice.id(),
                                invoice.customerId(),
                                invoice.subscriptionId(),
                                prevSubStatus.name(),
                                now
                        ));
                    }
                }
            }
        }

        eventPublisher.publishEvent(new PaymentSucceededEvent(
                savedTx.getId(),
                savedTx.getPaymentReference(),
                invoice.id(),
                invoice.customerId(),
                invoice.subscriptionId(),
                invoice.currency(),
                paymentAmount,
                attemptNumber,
                recoveredFromDunning,
                now
        ));

        metrics.recordPaymentSucceeded(Duration.ofNanos(System.nanoTime() - startNanos));
        LOGGER.info(
                "Captured mock payment: paymentRef={} invoiceId={} amount={} source={} attempt={} recoveredFromDunning={}",
                paymentRef, invoice.id(), paymentAmount, attemptSource, attemptNumber, recoveredFromDunning);
        return PaymentMapper.toPaymentDetail(savedTx, List.of(savedAttempt));
    }

    private PaymentDetail handleFailedPayment(
            InvoiceSummary invoice,
            DunningCaseEntity existingCase,
            String paymentRef,
            Money paymentAmount,
            PaymentAttemptSource attemptSource,
            int attemptNumber,
            MockPaymentOutcome outcome,
            String idempotencyKey,
            Instant now,
            long startNanos
    ) {
        invoiceManagementFacade.markInvoicePastDue(invoice.id(), outcome.failureCode());
        subscriptionManagementFacade.markPastDue(
                invoice.subscriptionId(),
                "PAYMENT_FAILED_" + outcome.failureCode(),
                "pay-pastdue:" + paymentRef
        );

        DunningCaseEntity dunningCase;
        boolean scheduleChanged = false;

        if (existingCase == null) {
            DunningPolicyEntity policy = dunningPolicyService.requireActivePolicyEntity();
            if (outcome.isRetryable()) {
                Instant retry1At = DunningScheduleCalculator
                        .computeNextRetryAt(now, 0, policy.getRetryOffsetsHours())
                        .orElseThrow();
                dunningCase = dunningCaseRepository.saveAndFlush(new DunningCaseEntity(
                        invoice.id(),
                        invoice.customerId(),
                        invoice.subscriptionId(),
                        policy.getId(),
                        DunningCaseStatus.RETRY_SCHEDULED,
                        now,
                        retry1At,
                        null,
                        outcome.failureCode(),
                        outcome.failureReason(),
                        now
                ));
                dunningEventRepository.save(new DunningEventEntity(
                        dunningCase.getId(), invoice.id(), invoice.customerId(), invoice.subscriptionId(),
                        DunningEventType.INITIAL_FAILURE, null, DunningCaseStatus.RETRY_SCHEDULED.name(),
                        0, retry1At,
                        Map.of("paymentReference", paymentRef, "failureCode", outcome.failureCode()),
                        now
                ));
                dunningEventRepository.save(new DunningEventEntity(
                        dunningCase.getId(), invoice.id(), invoice.customerId(), invoice.subscriptionId(),
                        DunningEventType.RETRY_SCHEDULED, DunningCaseStatus.RETRY_SCHEDULED.name(),
                        DunningCaseStatus.RETRY_SCHEDULED.name(), 1, retry1At,
                        Map.of("retryOffsetHours", policy.getRetryOffsetsHours().get(0)),
                        now
                ));
                scheduleChanged = true;
            } else {
                Instant suspensionAt = DunningScheduleCalculator
                        .computeSuspensionAt(now, policy.getSuspensionOffsetHours());
                dunningCase = dunningCaseRepository.saveAndFlush(new DunningCaseEntity(
                        invoice.id(),
                        invoice.customerId(),
                        invoice.subscriptionId(),
                        policy.getId(),
                        DunningCaseStatus.FINAL_WARNING,
                        now,
                        null,
                        suspensionAt,
                        outcome.failureCode(),
                        outcome.failureReason(),
                        now
                ));
                dunningEventRepository.save(new DunningEventEntity(
                        dunningCase.getId(), invoice.id(), invoice.customerId(), invoice.subscriptionId(),
                        DunningEventType.INITIAL_FAILURE, null, DunningCaseStatus.FINAL_WARNING.name(),
                        0, suspensionAt,
                        Map.of("paymentReference", paymentRef, "failureCode", outcome.failureCode(), "retryable", false),
                        now
                ));
                dunningEventRepository.save(new DunningEventEntity(
                        dunningCase.getId(), invoice.id(), invoice.customerId(), invoice.subscriptionId(),
                        DunningEventType.FINAL_WARNING_ISSUED, DunningCaseStatus.FINAL_WARNING.name(),
                        DunningCaseStatus.FINAL_WARNING.name(), 0, suspensionAt,
                        Map.of("reason", "NON_RETRYABLE_HARD_DECLINE"),
                        now
                ));
                metrics.recordFinalWarningIssued();
                scheduleChanged = true;
            }
        } else {
            dunningCase = existingCase;
            DunningPolicyEntity policy = dunningPolicyRepository.findById(dunningCase.getDunningPolicyId())
                    .orElseGet(dunningPolicyService::requireActivePolicyEntity);
            String prevStatus = dunningCase.getStatus().name();

            if (attemptSource == PaymentAttemptSource.DUNNING_RETRY && dunningCase.getStatus() == DunningCaseStatus.RETRY_SCHEDULED) {
                int completedRetries = Math.min(DunningScheduleCalculator.REQUIRED_RETRY_COUNT, attemptNumber);
                Optional<Instant> nextRetryOpt = outcome.isRetryable()
                        ? DunningScheduleCalculator.computeNextRetryAt(
                                dunningCase.getInitialFailureAt(), completedRetries, policy.getRetryOffsetsHours())
                        : Optional.empty();

                if (nextRetryOpt.isPresent()) {
                    Instant nextRetryAt = nextRetryOpt.get();
                    dunningCase.recordRetryFailureAndScheduleNext(
                            completedRetries, nextRetryAt, outcome.failureCode(), outcome.failureReason(), now);
                    dunningCaseRepository.saveAndFlush(dunningCase);

                    dunningEventRepository.save(new DunningEventEntity(
                            dunningCase.getId(), invoice.id(), invoice.customerId(), invoice.subscriptionId(),
                            DunningEventType.RETRY_FAILED, prevStatus, DunningCaseStatus.RETRY_SCHEDULED.name(),
                            completedRetries, nextRetryAt,
                            Map.of("paymentReference", paymentRef, "failureCode", outcome.failureCode()),
                            now
                    ));
                    dunningEventRepository.save(new DunningEventEntity(
                            dunningCase.getId(), invoice.id(), invoice.customerId(), invoice.subscriptionId(),
                            DunningEventType.RETRY_SCHEDULED, DunningCaseStatus.RETRY_SCHEDULED.name(),
                            DunningCaseStatus.RETRY_SCHEDULED.name(), completedRetries + 1, nextRetryAt,
                            Map.of("retryOffsetHours", policy.getRetryOffsetsHours().get(completedRetries)),
                            now
                    ));
                    scheduleChanged = true;
                } else {
                    Instant suspensionAt = DunningScheduleCalculator.computeSuspensionAt(
                            dunningCase.getInitialFailureAt(), policy.getSuspensionOffsetHours());
                    dunningCase.escalateToFinalWarning(
                            completedRetries, suspensionAt, outcome.failureCode(), outcome.failureReason(), now);
                    dunningCaseRepository.saveAndFlush(dunningCase);

                    dunningEventRepository.save(new DunningEventEntity(
                            dunningCase.getId(), invoice.id(), invoice.customerId(), invoice.subscriptionId(),
                            DunningEventType.RETRY_FAILED, prevStatus, DunningCaseStatus.FINAL_WARNING.name(),
                            completedRetries, suspensionAt,
                            Map.of("paymentReference", paymentRef, "failureCode", outcome.failureCode()),
                            now
                    ));
                    dunningEventRepository.save(new DunningEventEntity(
                            dunningCase.getId(), invoice.id(), invoice.customerId(), invoice.subscriptionId(),
                            DunningEventType.FINAL_WARNING_ISSUED, prevStatus, DunningCaseStatus.FINAL_WARNING.name(),
                            completedRetries, suspensionAt,
                            Map.of("reason", outcome.isRetryable() ? "RETRIES_EXHAUSTED" : "NON_RETRYABLE_HARD_DECLINE"),
                            now
                    ));
                    metrics.recordFinalWarningIssued();
                    scheduleChanged = true;
                }
            } else if (!outcome.isRetryable() && dunningCase.getStatus() == DunningCaseStatus.RETRY_SCHEDULED) {
                Instant suspensionAt = DunningScheduleCalculator.computeSuspensionAt(
                        dunningCase.getInitialFailureAt(), policy.getSuspensionOffsetHours());
                dunningCase.escalateToFinalWarning(
                        dunningCase.getRetriesCompleted(), suspensionAt, outcome.failureCode(), outcome.failureReason(), now);
                dunningCaseRepository.saveAndFlush(dunningCase);
                dunningEventRepository.save(new DunningEventEntity(
                        dunningCase.getId(), invoice.id(), invoice.customerId(), invoice.subscriptionId(),
                        DunningEventType.FINAL_WARNING_ISSUED, prevStatus, DunningCaseStatus.FINAL_WARNING.name(),
                        dunningCase.getRetriesCompleted(), suspensionAt,
                        Map.of("paymentReference", paymentRef, "reason", "MANUAL_HARD_DECLINE"),
                        now
                ));
                metrics.recordFinalWarningIssued();
                scheduleChanged = true;
            }
        }

        PaymentTransactionEntity savedTx = transactionRepository.saveAndFlush(new PaymentTransactionEntity(
                paymentRef,
                invoice.id(),
                invoice.customerId(),
                invoice.subscriptionId(),
                dunningCase.getId(),
                invoice.currency(),
                paymentAmount.amount(),
                PaymentStatus.FAILED,
                attemptSource,
                attemptNumber,
                outcome,
                outcome.isRetryable(),
                outcome.failureCode(),
                outcome.failureReason(),
                idempotencyKey,
                now,
                now
        ));

        PaymentAttemptEntity savedAttempt = attemptRepository.saveAndFlush(new PaymentAttemptEntity(
                savedTx.getId(),
                invoice.id(),
                invoice.customerId(),
                dunningCase.getId(),
                attemptNumber,
                attemptSource,
                outcome,
                PaymentAttemptStatus.FAILED,
                outcome.isRetryable(),
                outcome.failureCode(),
                outcome.failureReason(),
                paymentAmount.amount(),
                invoice.currency(),
                idempotencyKey,
                now
        ));

        eventPublisher.publishEvent(new PaymentFailedEvent(
                savedTx.getId(),
                savedTx.getPaymentReference(),
                invoice.id(),
                invoice.customerId(),
                invoice.subscriptionId(),
                dunningCase.getId(),
                invoice.currency(),
                paymentAmount,
                attemptNumber,
                outcome.isRetryable(),
                outcome.failureCode(),
                outcome.failureReason(),
                dunningCase.getStatus(),
                dunningCase.getNextRetryAt(),
                dunningCase.getSuspensionAt(),
                now
        ));

        if (scheduleChanged) {
            eventPublisher.publishEvent(new PaymentRetryScheduledEvent(
                    dunningCase.getId(),
                    invoice.id(),
                    invoice.customerId(),
                    invoice.subscriptionId(),
                    dunningCase.getStatus(),
                    dunningCase.getRetriesCompleted(),
                    dunningCase.getNextRetryAt(),
                    dunningCase.getSuspensionAt(),
                    now
            ));
        }

        metrics.recordPaymentFailed(outcome.isRetryable(), Duration.ofNanos(System.nanoTime() - startNanos));
        LOGGER.info(
                "Mock payment failed: paymentRef={} invoiceId={} code={} retryable={} dunningStatus={} retriesCompleted={} nextRetryAt={} suspensionAt={}",
                paymentRef, invoice.id(), outcome.failureCode(), outcome.isRetryable(),
                dunningCase.getStatus(), dunningCase.getRetriesCompleted(),
                dunningCase.getNextRetryAt(), dunningCase.getSuspensionAt());
        return PaymentMapper.toPaymentDetail(savedTx, List.of(savedAttempt));
    }

    private PaymentDetail loadPaymentDetail(PaymentTransactionEntity tx) {
        List<PaymentAttemptEntity> attempts = attemptRepository.findByPaymentTransactionIdOrderByAttemptedAtAsc(tx.getId());
        return PaymentMapper.toPaymentDetail(tx, attempts);
    }

    private static String normalizeOptionalKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return key.trim();
    }
}
