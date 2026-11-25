package com.meterflow.subscriptions.internal;

import com.meterflow.catalog.api.PlanCatalogFacade;
import com.meterflow.catalog.api.PlanVersionDetail;
import com.meterflow.catalog.api.PlanVersionStatus;
import com.meterflow.catalog.api.RatingEngineFacade;
import com.meterflow.catalog.api.RecurringPlanQuote;
import com.meterflow.catalog.api.RecurringQuoteRequest;
import com.meterflow.identity.api.CustomerStatus;
import com.meterflow.identity.api.CustomerSummary;
import com.meterflow.identity.api.IdentityQueryFacade;
import com.meterflow.identity.api.IdentityResourceNotFoundException;
import com.meterflow.shared.BillingInterval;
import com.meterflow.shared.BillingPeriod;
import com.meterflow.shared.CurrencyMismatchException;
import com.meterflow.shared.SupportedCurrency;
import com.meterflow.subscriptions.api.ConflictingSubscriptionException;
import com.meterflow.subscriptions.api.CreateSubscriptionCommand;
import com.meterflow.subscriptions.api.ImmediatePlanChangeCommand;
import com.meterflow.subscriptions.api.ImmediatePlanChangeResult;
import com.meterflow.subscriptions.api.InvalidSubscriptionRequestException;
import com.meterflow.subscriptions.api.InvalidSubscriptionTransitionException;
import com.meterflow.subscriptions.api.PlanChangeDirection;
import com.meterflow.subscriptions.api.SchedulePlanChangeCommand;
import com.meterflow.subscriptions.api.ScheduledChangeStatus;
import com.meterflow.subscriptions.api.ScheduledPlanChangeRecord;
import com.meterflow.subscriptions.api.SubscriptionManagementFacade;
import com.meterflow.subscriptions.api.SubscriptionNotFoundException;
import com.meterflow.subscriptions.api.SubscriptionStatus;
import com.meterflow.subscriptions.api.SubscriptionSummary;
import com.meterflow.subscriptions.api.TrialConversionPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SubscriptionManagementService implements SubscriptionManagementFacade {

    private static final int MAX_RAW_IDEMPOTENCY_KEY_LENGTH = 120;
    private static final Set<SubscriptionStatus> OPEN_STATUSES = EnumSet.of(
            SubscriptionStatus.TRIALING,
            SubscriptionStatus.ACTIVE,
            SubscriptionStatus.PAUSED,
            SubscriptionStatus.PAST_DUE,
            SubscriptionStatus.SUSPENDED
    );

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionPlanAssignmentRepository assignmentRepository;
    private final SubscriptionScheduledChangeRepository scheduledChangeRepository;
    private final SubscriptionProrationRecordRepository prorationRepository;
    private final SubscriptionStateTransitionRepository transitionRepository;
    private final IdentityQueryFacade identityQueryFacade;
    private final PlanCatalogFacade planCatalogFacade;
    private final RatingEngineFacade ratingEngineFacade;
    private final Clock clock;

    SubscriptionManagementService(
            SubscriptionRepository subscriptionRepository,
            SubscriptionPlanAssignmentRepository assignmentRepository,
            SubscriptionScheduledChangeRepository scheduledChangeRepository,
            SubscriptionProrationRecordRepository prorationRepository,
            SubscriptionStateTransitionRepository transitionRepository,
            IdentityQueryFacade identityQueryFacade,
            PlanCatalogFacade planCatalogFacade,
            RatingEngineFacade ratingEngineFacade,
            Clock clock
    ) {
        this.subscriptionRepository = subscriptionRepository;
        this.assignmentRepository = assignmentRepository;
        this.scheduledChangeRepository = scheduledChangeRepository;
        this.prorationRepository = prorationRepository;
        this.transitionRepository = transitionRepository;
        this.identityQueryFacade = identityQueryFacade;
        this.planCatalogFacade = planCatalogFacade;
        this.ratingEngineFacade = ratingEngineFacade;
        this.clock = clock;
    }

    @Override
    @Transactional
    public SubscriptionSummary createSubscription(CreateSubscriptionCommand command) {
        if (command == null) {
            throw new InvalidSubscriptionRequestException("CreateSubscriptionCommand must not be null");
        }
        if (command.customerId() == null) {
            throw new InvalidSubscriptionRequestException("customerId is required");
        }
        if (command.planVersionId() == null) {
            throw new InvalidSubscriptionRequestException("planVersionId is required");
        }

        String idempotencyKey = normalizeIdempotencyKey(command.idempotencyKey());

        subscriptionRepository.lockCustomerForSubscriptionCreation(command.customerId());

        if (idempotencyKey != null) {
            Optional<SubscriptionEntity> existing = subscriptionRepository
                    .findByCustomerIdAndCreationIdempotencyKey(command.customerId(), idempotencyKey);
            if (existing.isPresent()) {
                return SubscriptionMapper.toSummary(existing.get());
            }
        }

        CustomerSummary customer = identityQueryFacade.findCustomerById(command.customerId())
                .orElseThrow(() -> new IdentityResourceNotFoundException("Customer not found"));
        if (customer.status() != CustomerStatus.ACTIVE) {
            throw new InvalidSubscriptionRequestException("Customer account is not active");
        }

        if (subscriptionRepository.existsByCustomerIdAndStatusIn(command.customerId(), OPEN_STATUSES)) {
            throw new ConflictingSubscriptionException(
                    "Customer already has an active or open subscription; terminate it before creating another"
            );
        }

        PlanVersionDetail planVersion = requirePublishedPlanVersion(command.planVersionId());
        SupportedCurrency currency = validateAndResolveCurrency(customer.currency(), command.currency(), planVersion.currency());

        BillingInterval interval = command.billingInterval() != null ? command.billingInterval() : BillingInterval.MONTHLY;
        ZoneId billingZone = parseBillingTimezone(command.billingTimezone());
        int seatQuantity = command.seatQuantity() != null ? command.seatQuantity() : 1;
        if (seatQuantity < 0) {
            throw new InvalidSubscriptionRequestException("seatQuantity must not be negative");
        }

        Instant now = nowUtc();
        Instant startAt = command.startAt() != null ? normalizeInstant(command.startAt()) : now;
        Instant trialEndAt = command.trialEndAt() != null ? normalizeInstant(command.trialEndAt()) : null;
        TrialConversionPolicy trialPolicy = command.trialConversionPolicy() != null
                ? command.trialConversionPolicy()
                : TrialConversionPolicy.EXPIRE_ON_END;

        SubscriptionStatus initialStatus;
        Instant trialStart = null;
        Instant periodStart = null;
        Instant periodEnd = null;
        String assignmentReason;

        if (trialEndAt != null) {
            if (!trialEndAt.isAfter(startAt)) {
                throw new InvalidSubscriptionRequestException("trialEndAt must be strictly after startAt");
            }
            initialStatus = SubscriptionStatus.TRIALING;
            trialStart = startAt;
            assignmentReason = "INITIAL_TRIAL";
        } else {
            BillingPeriod period = BillingPeriod.startingAt(startAt, interval, billingZone);
            initialStatus = SubscriptionStatus.ACTIVE;
            periodStart = period.start();
            periodEnd = period.end();
            assignmentReason = "INITIAL_PAID";
        }

        SubscriptionEntity subscription = new SubscriptionEntity(
                customer.id(),
                planVersion.id(),
                currency,
                interval,
                billingZone.getId(),
                seatQuantity,
                initialStatus,
                trialStart,
                trialEndAt,
                trialPolicy,
                periodStart,
                periodEnd,
                idempotencyKey,
                now
        );
        SubscriptionEntity saved = subscriptionRepository.saveAndFlush(subscription);

        assignmentRepository.save(new SubscriptionPlanAssignmentEntity(
                saved.getId(),
                planVersion.id(),
                seatQuantity,
                startAt,
                null,
                assignmentReason,
                now
        ));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("planVersionId", planVersion.id().toString());
        metadata.put("planCode", planVersion.planCode());
        metadata.put("planVersionNumber", planVersion.versionNumber());
        metadata.put("billingInterval", interval.name());
        metadata.put("billingTimezone", billingZone.getId());
        metadata.put("seatQuantity", seatQuantity);
        if (trialEndAt != null) {
            metadata.put("trialEndAt", trialEndAt.toString());
            metadata.put("trialConversionPolicy", trialPolicy.name());
        }

        transitionRepository.save(new SubscriptionStateTransitionEntity(
                saved.getId(),
                null,
                initialStatus,
                trialEndAt != null ? "SUBSCRIPTION_CREATED_TRIAL" : "SUBSCRIPTION_CREATED_ACTIVE",
                startAt,
                scopedTransitionKey("create", idempotencyKey),
                metadata,
                now
        ));

        return SubscriptionMapper.toSummary(saved);
    }

    @Override
    @Transactional
    public SubscriptionSummary convertTrial(UUID subscriptionId, String idempotencyKey) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        String transitionKey = scopedTransitionKey("convert-trial", normalizedKey);

        SubscriptionEntity subscription = lockSubscription(subscriptionId);
        if (transitionKey != null
                && transitionRepository.findBySubscriptionIdAndIdempotencyKey(subscriptionId, transitionKey).isPresent()) {
            return SubscriptionMapper.toSummary(subscription);
        }

        requireTransitionAllowed(subscription.getStatus(), SubscriptionStatus.ACTIVE, "convert trial");
        if (subscription.getStatus() != SubscriptionStatus.TRIALING) {
            throw new InvalidSubscriptionTransitionException(
                    "Only TRIALING subscriptions can be converted from trial; current status is " + subscription.getStatus()
            );
        }

        Instant now = nowUtc();
        if (subscription.getTrialEndAt() != null && !now.isBefore(subscription.getTrialEndAt())) {
            throw new InvalidSubscriptionTransitionException(
                    "Trial ended at " + subscription.getTrialEndAt() + " and can no longer be converted manually"
            );
        }

        ZoneId zone = parseBillingTimezone(subscription.getBillingTimezone());
        BillingPeriod period = BillingPeriod.startingAt(now, subscription.getBillingInterval(), zone);

        closeCurrentAssignment(subscriptionId, now);
        assignmentRepository.save(new SubscriptionPlanAssignmentEntity(
                subscriptionId,
                subscription.getCurrentPlanVersionId(),
                subscription.getSeatQuantity(),
                period.start(),
                null,
                "TRIAL_CONVERSION",
                now
        ));

        subscription.activateFromTrial(period.start(), period.end(), now);
        subscriptionRepository.save(subscription);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("planVersionId", subscription.getCurrentPlanVersionId().toString());
        metadata.put("periodStart", period.start().toString());
        metadata.put("periodEnd", period.end().toString());

        transitionRepository.save(new SubscriptionStateTransitionEntity(
                subscriptionId,
                SubscriptionStatus.TRIALING,
                SubscriptionStatus.ACTIVE,
                "TRIAL_CONVERTED",
                now,
                transitionKey,
                metadata,
                now
        ));

        return SubscriptionMapper.toSummary(subscription);
    }

    @Override
    @Transactional
    public SubscriptionSummary cancelTrial(UUID subscriptionId, String idempotencyKey) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        String transitionKey = scopedTransitionKey("cancel-trial", normalizedKey);

        SubscriptionEntity subscription = lockSubscription(subscriptionId);
        if (transitionKey != null
                && transitionRepository.findBySubscriptionIdAndIdempotencyKey(subscriptionId, transitionKey).isPresent()) {
            return SubscriptionMapper.toSummary(subscription);
        }

        if (subscription.getStatus() != SubscriptionStatus.TRIALING) {
            throw new InvalidSubscriptionTransitionException(
                    "Only TRIALING subscriptions can cancel a trial; current status is " + subscription.getStatus()
            );
        }

        Instant now = nowUtc();
        closeCurrentAssignment(subscriptionId, now);
        subscription.cancelNow(now, now);
        subscriptionRepository.save(subscription);

        transitionRepository.save(new SubscriptionStateTransitionEntity(
                subscriptionId,
                SubscriptionStatus.TRIALING,
                SubscriptionStatus.CANCELLED,
                "TRIAL_CANCELLED",
                now,
                transitionKey,
                Map.of("planVersionId", subscription.getCurrentPlanVersionId().toString()),
                now
        ));

        return SubscriptionMapper.toSummary(subscription);
    }

    @Override
    @Transactional
    public ImmediatePlanChangeResult changePlanImmediately(UUID subscriptionId, ImmediatePlanChangeCommand command) {
        if (command == null || command.targetPlanVersionId() == null) {
            throw new InvalidSubscriptionRequestException("targetPlanVersionId is required");
        }

        String normalizedKey = normalizeIdempotencyKey(command.idempotencyKey());
        SubscriptionEntity subscription = lockSubscription(subscriptionId);

        if (normalizedKey != null) {
            Optional<SubscriptionProrationRecordEntity> existing = prorationRepository
                    .findBySubscriptionIdAndIdempotencyKey(subscriptionId, normalizedKey);
            if (existing.isPresent()) {
                return new ImmediatePlanChangeResult(
                        SubscriptionMapper.toSummary(subscription),
                        SubscriptionMapper.toProrationRecord(existing.get())
                );
            }
        }

        if (subscription.getStatus() != SubscriptionStatus.ACTIVE) {
            throw new InvalidSubscriptionTransitionException(
                    "Immediate plan changes require ACTIVE status; current status is " + subscription.getStatus()
            );
        }
        if (subscription.isCancelAtPeriodEnd()) {
            throw new ConflictingSubscriptionException(
                    "Cannot change plan while subscription is scheduled for cancellation at period end"
            );
        }

        PlanVersionDetail targetPlanVersion = requirePublishedPlanVersion(command.targetPlanVersionId());
        if (targetPlanVersion.currency() != subscription.getCurrency()) {
            throw new CurrencyMismatchException(subscription.getCurrency(), targetPlanVersion.currency());
        }

        int targetSeatQuantity = command.targetSeatQuantity() != null
                ? command.targetSeatQuantity()
                : subscription.getSeatQuantity();
        if (targetSeatQuantity < 0) {
            throw new InvalidSubscriptionRequestException("targetSeatQuantity must not be negative");
        }
        if (targetPlanVersion.id().equals(subscription.getCurrentPlanVersionId())
                && targetSeatQuantity == subscription.getSeatQuantity()) {
            throw new InvalidSubscriptionRequestException(
                    "Target plan version and seat quantity match the current subscription assignment"
            );
        }

        Instant now = nowUtc();
        Instant effectiveAt = command.effectiveAt() != null ? normalizeInstant(command.effectiveAt()) : now;
        ZoneId zone = parseBillingTimezone(subscription.getBillingTimezone());
        BillingPeriod period = new BillingPeriod(
                subscription.getCurrentPeriodStart(),
                subscription.getCurrentPeriodEnd(),
                subscription.getBillingInterval(),
                zone
        );

        RecurringPlanQuote previousQuote = ratingEngineFacade.rateRecurringCharges(
                new RecurringQuoteRequest(subscription.getCurrentPlanVersionId(), subscription.getSeatQuantity())
        );
        RecurringPlanQuote newQuote = ratingEngineFacade.rateRecurringCharges(
                new RecurringQuoteRequest(targetPlanVersion.id(), targetSeatQuantity)
        );

        Set<String> excludedUsageCharges = new LinkedHashSet<>();
        excludedUsageCharges.addAll(previousQuote.nonProratableUsageChargeCodes());
        excludedUsageCharges.addAll(newQuote.nonProratableUsageChargeCodes());

        ProrationCalculator.CalculationResult calculation = ProrationCalculator.calculate(
                period,
                effectiveAt,
                previousQuote.recurringTotalAmount(),
                newQuote.recurringTotalAmount(),
                List.copyOf(excludedUsageCharges)
        );

        PlanChangeDirection direction = determineDirection(
                previousQuote.recurringTotalAmount().amount(),
                newQuote.recurringTotalAmount().amount()
        );

        // Supersede any existing PENDING scheduled plan change cleanly
        scheduledChangeRepository.findBySubscriptionIdAndStatus(subscriptionId, ScheduledChangeStatus.PENDING)
                .ifPresent(pending -> {
                    pending.markSuperseded(now);
                    scheduledChangeRepository.saveAndFlush(pending);
                });

        UUID previousPlanVersionId = subscription.getCurrentPlanVersionId();
        int previousSeatQuantity = subscription.getSeatQuantity();

        closeCurrentAssignment(subscriptionId, effectiveAt);
        assignmentRepository.save(new SubscriptionPlanAssignmentEntity(
                subscriptionId,
                targetPlanVersion.id(),
                targetSeatQuantity,
                effectiveAt,
                null,
                "IMMEDIATE_" + direction.name(),
                now
        ));

        SubscriptionProrationRecordEntity prorationEntity = prorationRepository.save(new SubscriptionProrationRecordEntity(
                subscriptionId,
                previousPlanVersionId,
                targetPlanVersion.id(),
                previousSeatQuantity,
                targetSeatQuantity,
                period.start(),
                period.end(),
                effectiveAt,
                calculation.totalPeriodSeconds(),
                calculation.remainingSeconds(),
                calculation.prorationFactor(),
                calculation.previousPlanRecurringAmount().amount(),
                calculation.newPlanRecurringAmount().amount(),
                calculation.creditAmount().amount(),
                calculation.debitAmount().amount(),
                calculation.netProrationAmount().amount(),
                subscription.getCurrency(),
                calculation.nonProratableUsageCharges(),
                calculation.calculationPolicy(),
                normalizedKey,
                now
        ));

        subscription.applyPlanChange(targetPlanVersion.id(), targetSeatQuantity, now);
        subscriptionRepository.save(subscription);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("previousPlanVersionId", previousPlanVersionId.toString());
        metadata.put("newPlanVersionId", targetPlanVersion.id().toString());
        metadata.put("previousSeatQuantity", previousSeatQuantity);
        metadata.put("newSeatQuantity", targetSeatQuantity);
        metadata.put("direction", direction.name());
        metadata.put("prorationRecordId", prorationEntity.getId().toString());
        metadata.put("netProrationAmount", calculation.netProrationAmount().amount().toPlainString());
        metadata.put("currency", subscription.getCurrency().name());

        transitionRepository.save(new SubscriptionStateTransitionEntity(
                subscriptionId,
                SubscriptionStatus.ACTIVE,
                SubscriptionStatus.ACTIVE,
                "IMMEDIATE_" + direction.name(),
                effectiveAt,
                scopedTransitionKey("immediate-change", normalizedKey),
                metadata,
                now
        ));

        return new ImmediatePlanChangeResult(
                SubscriptionMapper.toSummary(subscription),
                SubscriptionMapper.toProrationRecord(prorationEntity)
        );
    }

    @Override
    @Transactional
    public ScheduledPlanChangeRecord schedulePlanChange(UUID subscriptionId, SchedulePlanChangeCommand command) {
        if (command == null || command.targetPlanVersionId() == null) {
            throw new InvalidSubscriptionRequestException("targetPlanVersionId is required");
        }

        String normalizedKey = normalizeIdempotencyKey(command.idempotencyKey());
        SubscriptionEntity subscription = lockSubscription(subscriptionId);

        if (normalizedKey != null) {
            Optional<SubscriptionScheduledChangeEntity> existingByKey = scheduledChangeRepository
                    .findBySubscriptionIdAndIdempotencyKey(subscriptionId, normalizedKey);
            if (existingByKey.isPresent()) {
                return SubscriptionMapper.toScheduledChangeRecord(existingByKey.get());
            }
        }

        if (subscription.getStatus() != SubscriptionStatus.ACTIVE) {
            throw new InvalidSubscriptionTransitionException(
                    "Scheduling a plan change requires ACTIVE status; current status is " + subscription.getStatus()
            );
        }
        if (subscription.isCancelAtPeriodEnd()) {
            throw new ConflictingSubscriptionException(
                    "Cannot schedule a plan change while cancellation at period end is active"
            );
        }

        PlanVersionDetail targetPlanVersion = requirePublishedPlanVersion(command.targetPlanVersionId());
        if (targetPlanVersion.currency() != subscription.getCurrency()) {
            throw new CurrencyMismatchException(subscription.getCurrency(), targetPlanVersion.currency());
        }

        int targetSeatQuantity = command.targetSeatQuantity() != null
                ? command.targetSeatQuantity()
                : subscription.getSeatQuantity();
        if (targetSeatQuantity < 0) {
            throw new InvalidSubscriptionRequestException("targetSeatQuantity must not be negative");
        }
        if (targetPlanVersion.id().equals(subscription.getCurrentPlanVersionId())
                && targetSeatQuantity == subscription.getSeatQuantity()) {
            throw new InvalidSubscriptionRequestException(
                    "Target plan version and seat quantity match the current subscription assignment"
            );
        }

        Instant now = nowUtc();
        Optional<SubscriptionScheduledChangeEntity> existingPending = scheduledChangeRepository
                .findBySubscriptionIdAndStatus(subscriptionId, ScheduledChangeStatus.PENDING);
        if (existingPending.isPresent()) {
            if (!command.replaceExisting()) {
                throw new ConflictingSubscriptionException(
                        "A pending scheduled plan change already exists; cancel it first or set replaceExisting=true"
                );
            }
            SubscriptionScheduledChangeEntity previousPending = existingPending.get();
            previousPending.markSuperseded(now);
            // Flush BEFORE inserting the replacement row so PostgreSQL partial unique index sees SUPERSEDED.
            scheduledChangeRepository.saveAndFlush(previousPending);
        }

        RecurringPlanQuote currentQuote = ratingEngineFacade.rateRecurringCharges(
                new RecurringQuoteRequest(subscription.getCurrentPlanVersionId(), subscription.getSeatQuantity())
        );
        RecurringPlanQuote targetQuote = ratingEngineFacade.rateRecurringCharges(
                new RecurringQuoteRequest(targetPlanVersion.id(), targetSeatQuantity)
        );
        PlanChangeDirection direction = determineDirection(
                currentQuote.recurringTotalAmount().amount(),
                targetQuote.recurringTotalAmount().amount()
        );

        Instant effectiveAt = subscription.getCurrentPeriodEnd();
        SubscriptionScheduledChangeEntity scheduled = scheduledChangeRepository.save(
                new SubscriptionScheduledChangeEntity(
                        subscriptionId,
                        subscription.getCurrentPlanVersionId(),
                        targetPlanVersion.id(),
                        targetSeatQuantity,
                        direction,
                        effectiveAt,
                        normalizedKey,
                        now
                )
        );

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scheduledChangeId", scheduled.getId().toString());
        metadata.put("currentPlanVersionId", subscription.getCurrentPlanVersionId().toString());
        metadata.put("targetPlanVersionId", targetPlanVersion.id().toString());
        metadata.put("targetSeatQuantity", targetSeatQuantity);
        metadata.put("direction", direction.name());
        metadata.put("effectiveAt", effectiveAt.toString());

        transitionRepository.save(new SubscriptionStateTransitionEntity(
                subscriptionId,
                SubscriptionStatus.ACTIVE,
                SubscriptionStatus.ACTIVE,
                "PLAN_CHANGE_SCHEDULED",
                effectiveAt,
                scopedTransitionKey("schedule-change", normalizedKey),
                metadata,
                now
        ));

        return SubscriptionMapper.toScheduledChangeRecord(scheduled);
    }

    @Override
    @Transactional
    public ScheduledPlanChangeRecord cancelScheduledPlanChange(UUID subscriptionId, String idempotencyKey) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        String transitionKey = scopedTransitionKey("cancel-schedule", normalizedKey);

        SubscriptionEntity subscription = lockSubscription(subscriptionId);
        if (transitionKey != null
                && transitionRepository.findBySubscriptionIdAndIdempotencyKey(subscriptionId, transitionKey).isPresent()) {
            SubscriptionScheduledChangeEntity cancelled = scheduledChangeRepository
                    .findFirstBySubscriptionIdAndStatusOrderByUpdatedAtDesc(subscriptionId, ScheduledChangeStatus.CANCELLED)
                    .orElseThrow(() -> new SubscriptionNotFoundException("Cancelled scheduled plan change not found"));
            return SubscriptionMapper.toScheduledChangeRecord(cancelled);
        }

        SubscriptionScheduledChangeEntity pending = scheduledChangeRepository
                .findBySubscriptionIdAndStatus(subscriptionId, ScheduledChangeStatus.PENDING)
                .orElseThrow(() -> new SubscriptionNotFoundException(
                        "No pending scheduled plan change exists for subscription"
                ));

        Instant now = nowUtc();
        pending.markCancelled(now);
        scheduledChangeRepository.save(pending);

        transitionRepository.save(new SubscriptionStateTransitionEntity(
                subscriptionId,
                subscription.getStatus(),
                subscription.getStatus(),
                "SCHEDULED_PLAN_CHANGE_CANCELLED",
                now,
                transitionKey,
                Map.of("scheduledChangeId", pending.getId().toString()),
                now
        ));

        return SubscriptionMapper.toScheduledChangeRecord(pending);
    }

    @Override
    @Transactional
    public SubscriptionSummary pauseSubscription(UUID subscriptionId, String idempotencyKey) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        String transitionKey = scopedTransitionKey("pause", normalizedKey);

        SubscriptionEntity subscription = lockSubscription(subscriptionId);
        if (transitionKey != null
                && transitionRepository.findBySubscriptionIdAndIdempotencyKey(subscriptionId, transitionKey).isPresent()) {
            return SubscriptionMapper.toSummary(subscription);
        }

        requireTransitionAllowed(subscription.getStatus(), SubscriptionStatus.PAUSED, "pause");
        if (subscription.isCancelAtPeriodEnd()) {
            throw new ConflictingSubscriptionException(
                    "Cannot pause a subscription scheduled for cancellation at period end"
            );
        }
        if (scheduledChangeRepository
                .findBySubscriptionIdAndStatus(subscriptionId, ScheduledChangeStatus.PENDING)
                .isPresent()) {
            throw new ConflictingSubscriptionException(
                    "Cancel the pending scheduled plan change before pausing the subscription"
            );
        }

        Instant now = nowUtc();
        long remainingSeconds = subscription.getCurrentPeriodEnd() != null
                && now.isBefore(subscription.getCurrentPeriodEnd())
                ? Duration.between(now, subscription.getCurrentPeriodEnd()).getSeconds()
                : 0L;

        subscription.pause(now, remainingSeconds, now);
        subscriptionRepository.save(subscription);

        transitionRepository.save(new SubscriptionStateTransitionEntity(
                subscriptionId,
                SubscriptionStatus.ACTIVE,
                SubscriptionStatus.PAUSED,
                "SUBSCRIPTION_PAUSED",
                now,
                transitionKey,
                Map.of("pausedRemainingSeconds", remainingSeconds),
                now
        ));

        return SubscriptionMapper.toSummary(subscription);
    }

    @Override
    @Transactional
    public SubscriptionSummary resumeSubscription(UUID subscriptionId, String idempotencyKey) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        String transitionKey = scopedTransitionKey("resume", normalizedKey);

        SubscriptionEntity subscription = lockSubscription(subscriptionId);
        if (transitionKey != null
                && transitionRepository.findBySubscriptionIdAndIdempotencyKey(subscriptionId, transitionKey).isPresent()) {
            return SubscriptionMapper.toSummary(subscription);
        }

        if (subscription.getStatus() != SubscriptionStatus.PAUSED) {
            throw new InvalidSubscriptionTransitionException(
                    "Only PAUSED subscriptions can be resumed; current status is " + subscription.getStatus()
            );
        }

        Instant now = nowUtc();
        long remainingSeconds = subscription.getPausedRemainingSeconds() != null
                ? subscription.getPausedRemainingSeconds()
                : 0L;

        Instant newPeriodStart;
        Instant newPeriodEnd;
        if (remainingSeconds > 0L) {
            newPeriodStart = now;
            newPeriodEnd = now.plusSeconds(remainingSeconds);
        } else {
            BillingPeriod freshPeriod = BillingPeriod.startingAt(
                    now,
                    subscription.getBillingInterval(),
                    parseBillingTimezone(subscription.getBillingTimezone())
            );
            newPeriodStart = freshPeriod.start();
            newPeriodEnd = freshPeriod.end();
        }

        closeCurrentAssignment(subscriptionId, now);
        assignmentRepository.save(new SubscriptionPlanAssignmentEntity(
                subscriptionId,
                subscription.getCurrentPlanVersionId(),
                subscription.getSeatQuantity(),
                newPeriodStart,
                null,
                "RESUMED_PERIOD_SHIFT",
                now
        ));

        subscription.resume(newPeriodStart, newPeriodEnd, now);
        subscriptionRepository.save(subscription);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("restoredRemainingSeconds", remainingSeconds);
        metadata.put("newPeriodStart", newPeriodStart.toString());
        metadata.put("newPeriodEnd", newPeriodEnd.toString());

        transitionRepository.save(new SubscriptionStateTransitionEntity(
                subscriptionId,
                SubscriptionStatus.PAUSED,
                SubscriptionStatus.ACTIVE,
                "SUBSCRIPTION_RESUMED",
                now,
                transitionKey,
                metadata,
                now
        ));

        return SubscriptionMapper.toSummary(subscription);
    }

    @Override
    @Transactional
    public SubscriptionSummary cancelImmediately(UUID subscriptionId, String idempotencyKey) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        String transitionKey = scopedTransitionKey("cancel-now", normalizedKey);

        SubscriptionEntity subscription = lockSubscription(subscriptionId);
        if (transitionKey != null
                && transitionRepository.findBySubscriptionIdAndIdempotencyKey(subscriptionId, transitionKey).isPresent()) {
            return SubscriptionMapper.toSummary(subscription);
        }

        SubscriptionStatus previousStatus = subscription.getStatus();
        requireTransitionAllowed(previousStatus, SubscriptionStatus.CANCELLED, "cancel immediately");

        Instant now = nowUtc();
        scheduledChangeRepository.findBySubscriptionIdAndStatus(subscriptionId, ScheduledChangeStatus.PENDING)
                .ifPresent(pending -> {
                    pending.markCancelled(now);
                    scheduledChangeRepository.save(pending);
                });

        closeCurrentAssignment(subscriptionId, now);
        subscription.cancelNow(now, now);
        subscriptionRepository.save(subscription);

        transitionRepository.save(new SubscriptionStateTransitionEntity(
                subscriptionId,
                previousStatus,
                SubscriptionStatus.CANCELLED,
                "CANCELLED_IMMEDIATELY",
                now,
                transitionKey,
                Map.of("cancelledAt", now.toString()),
                now
        ));

        return SubscriptionMapper.toSummary(subscription);
    }

    @Override
    @Transactional
    public SubscriptionSummary cancelAtPeriodEnd(UUID subscriptionId, String idempotencyKey) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        String transitionKey = scopedTransitionKey("cancel-eop", normalizedKey);

        SubscriptionEntity subscription = lockSubscription(subscriptionId);
        if (transitionKey != null
                && transitionRepository.findBySubscriptionIdAndIdempotencyKey(subscriptionId, transitionKey).isPresent()) {
            return SubscriptionMapper.toSummary(subscription);
        }

        if (subscription.getStatus() != SubscriptionStatus.ACTIVE) {
            throw new InvalidSubscriptionTransitionException(
                    "End-of-period cancellation requires ACTIVE status; current status is " + subscription.getStatus()
            );
        }
        if (subscription.isCancelAtPeriodEnd()) {
            return SubscriptionMapper.toSummary(subscription);
        }

        Instant now = nowUtc();
        scheduledChangeRepository.findBySubscriptionIdAndStatus(subscriptionId, ScheduledChangeStatus.PENDING)
                .ifPresent(pending -> {
                    pending.markCancelled(now);
                    scheduledChangeRepository.save(pending);
                });

        subscription.scheduleCancelAtPeriodEnd(now);
        subscriptionRepository.save(subscription);

        transitionRepository.save(new SubscriptionStateTransitionEntity(
                subscriptionId,
                SubscriptionStatus.ACTIVE,
                SubscriptionStatus.ACTIVE,
                "CANCEL_AT_PERIOD_END_SCHEDULED",
                subscription.getCurrentPeriodEnd(),
                transitionKey,
                Map.of("scheduledCancellationAt", subscription.getCurrentPeriodEnd().toString()),
                now
        ));

        return SubscriptionMapper.toSummary(subscription);
    }

    @Override
    @Transactional
    public SubscriptionSummary markPastDue(UUID subscriptionId, String reason, String idempotencyKey) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        String transitionKey = scopedTransitionKey("past-due", normalizedKey);

        SubscriptionEntity subscription = lockSubscription(subscriptionId);
        if (transitionKey != null
                && transitionRepository.findBySubscriptionIdAndIdempotencyKey(subscriptionId, transitionKey).isPresent()) {
            return SubscriptionMapper.toSummary(subscription);
        }

        SubscriptionStatus current = subscription.getStatus();
        if (current == SubscriptionStatus.PAST_DUE
                || current == SubscriptionStatus.SUSPENDED
                || current.isTerminal()) {
            // Idempotent no-op if already past-due/suspended or terminal (never mutate CANCELLED/EXPIRED).
            return SubscriptionMapper.toSummary(subscription);
        }
        if (current != SubscriptionStatus.ACTIVE) {
            return SubscriptionMapper.toSummary(subscription);
        }

        Instant now = nowUtc();
        subscription.markPastDue(now);
        subscriptionRepository.saveAndFlush(subscription);

        String effectiveReason = (reason == null || reason.isBlank()) ? "PAYMENT_FAILED_PAST_DUE" : reason.trim();
        transitionRepository.save(new SubscriptionStateTransitionEntity(
                subscriptionId,
                current,
                SubscriptionStatus.PAST_DUE,
                effectiveReason.length() > 80 ? effectiveReason.substring(0, 80) : effectiveReason,
                now,
                transitionKey,
                Map.of("trigger", "payments_dunning"),
                now
        ));

        return SubscriptionMapper.toSummary(subscription);
    }

    @Override
    @Transactional
    public SubscriptionSummary suspendForNonPayment(UUID subscriptionId, String reason, String idempotencyKey) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        String transitionKey = scopedTransitionKey("suspend", normalizedKey);

        SubscriptionEntity subscription = lockSubscription(subscriptionId);
        if (transitionKey != null
                && transitionRepository.findBySubscriptionIdAndIdempotencyKey(subscriptionId, transitionKey).isPresent()) {
            return SubscriptionMapper.toSummary(subscription);
        }

        SubscriptionStatus current = subscription.getStatus();
        if (current == SubscriptionStatus.SUSPENDED || current.isTerminal()) {
            // Idempotent no-op if already suspended or terminal (never mutate CANCELLED/EXPIRED).
            return SubscriptionMapper.toSummary(subscription);
        }
        if (current != SubscriptionStatus.PAST_DUE && current != SubscriptionStatus.ACTIVE) {
            return SubscriptionMapper.toSummary(subscription);
        }

        Instant now = nowUtc();
        subscription.suspendForNonPayment(now);
        subscriptionRepository.saveAndFlush(subscription);

        String effectiveReason = (reason == null || reason.isBlank()) ? "DUNNING_EXHAUSTED_SUSPENDED" : reason.trim();
        transitionRepository.save(new SubscriptionStateTransitionEntity(
                subscriptionId,
                current,
                SubscriptionStatus.SUSPENDED,
                effectiveReason.length() > 80 ? effectiveReason.substring(0, 80) : effectiveReason,
                now,
                transitionKey,
                Map.of("trigger", "payments_dunning_suspension"),
                now
        ));

        return SubscriptionMapper.toSummary(subscription);
    }

    @Override
    @Transactional
    public SubscriptionSummary reactivateAfterPayment(UUID subscriptionId, String reason, String idempotencyKey) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        String transitionKey = scopedTransitionKey("reactivate", normalizedKey);

        SubscriptionEntity subscription = lockSubscription(subscriptionId);
        if (transitionKey != null
                && transitionRepository.findBySubscriptionIdAndIdempotencyKey(subscriptionId, transitionKey).isPresent()) {
            return SubscriptionMapper.toSummary(subscription);
        }

        SubscriptionStatus current = subscription.getStatus();
        // Strictly protect terminal CANCELLED / EXPIRED and manually PAUSED subscriptions from auto-reactivation.
        if (current != SubscriptionStatus.PAST_DUE && current != SubscriptionStatus.SUSPENDED) {
            return SubscriptionMapper.toSummary(subscription);
        }

        Instant now = nowUtc();
        subscription.reactivateToActive(now);
        subscriptionRepository.saveAndFlush(subscription);

        String effectiveReason = (reason == null || reason.isBlank()) ? "PAYMENT_RECOVERED_REACTIVATED" : reason.trim();
        transitionRepository.save(new SubscriptionStateTransitionEntity(
                subscriptionId,
                current,
                SubscriptionStatus.ACTIVE,
                effectiveReason.length() > 80 ? effectiveReason.substring(0, 80) : effectiveReason,
                now,
                transitionKey,
                Map.of("trigger", "payments_recovery"),
                now
        ));

        return SubscriptionMapper.toSummary(subscription);
    }

    private SubscriptionEntity lockSubscription(UUID subscriptionId) {
        if (subscriptionId == null) {
            throw new InvalidSubscriptionRequestException("subscriptionId is required");
        }
        return subscriptionRepository.findByIdForUpdate(subscriptionId)
                .orElseThrow(() -> new SubscriptionNotFoundException("Subscription not found"));
    }

    private PlanVersionDetail requirePublishedPlanVersion(UUID planVersionId) {
        PlanVersionDetail version = planCatalogFacade.findPlanVersionById(planVersionId)
                .orElseThrow(() -> new InvalidSubscriptionRequestException("Plan version not found: " + planVersionId));
        if (version.status() != PlanVersionStatus.PUBLISHED) {
            throw new InvalidSubscriptionRequestException(
                    "Only PUBLISHED plan versions may be assigned to subscriptions; status is " + version.status()
            );
        }
        return version;
    }

    private SupportedCurrency validateAndResolveCurrency(
            SupportedCurrency customerCurrency,
            SupportedCurrency requestedCurrency,
            SupportedCurrency planCurrency
    ) {
        if (requestedCurrency != null && requestedCurrency != customerCurrency) {
            throw new CurrencyMismatchException(customerCurrency, requestedCurrency);
        }
        if (planCurrency != customerCurrency) {
            throw new CurrencyMismatchException(customerCurrency, planCurrency);
        }
        return customerCurrency;
    }

    private void closeCurrentAssignment(UUID subscriptionId, Instant effectiveTo) {
        assignmentRepository
                .findFirstBySubscriptionIdAndEffectiveToIsNullOrderByEffectiveFromDesc(subscriptionId)
                .ifPresent(active -> {
                    if (effectiveTo.isBefore(active.getEffectiveFrom())) {
                        throw new InvalidSubscriptionRequestException(
                                "effectiveAt (" + effectiveTo + ") precedes current plan assignment start ("
                                        + active.getEffectiveFrom() + ")"
                        );
                    }
                    active.closeAt(effectiveTo);
                    assignmentRepository.save(active);
                });
    }

    private static PlanChangeDirection determineDirection(
            java.math.BigDecimal currentAmount,
            java.math.BigDecimal targetAmount
    ) {
        int cmp = targetAmount.compareTo(currentAmount);
        if (cmp > 0) {
            return PlanChangeDirection.UPGRADE;
        }
        if (cmp < 0) {
            return PlanChangeDirection.DOWNGRADE;
        }
        return PlanChangeDirection.PLAN_CHANGE;
    }

    private static void requireTransitionAllowed(
            SubscriptionStatus current,
            SubscriptionStatus target,
            String operation
    ) {
        if (!current.canTransitionTo(target)) {
            throw new InvalidSubscriptionTransitionException(
                    "Cannot " + operation + ": transition from " + current + " to " + target + " is not permitted"
            );
        }
    }

    private static ZoneId parseBillingTimezone(String timezoneId) {
        try {
            return BillingPeriod.parseTimezone(timezoneId);
        } catch (IllegalArgumentException ex) {
            throw new InvalidSubscriptionRequestException(ex.getMessage());
        }
    }

    private static String normalizeIdempotencyKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return null;
        }
        String trimmed = rawKey.trim();
        if (trimmed.length() > MAX_RAW_IDEMPOTENCY_KEY_LENGTH) {
            throw new InvalidSubscriptionRequestException(
                    "Idempotency key must not exceed " + MAX_RAW_IDEMPOTENCY_KEY_LENGTH + " characters"
            );
        }
        return trimmed;
    }

    private static String scopedTransitionKey(String scope, String normalizedKey) {
        return normalizedKey == null ? null : scope + ":" + normalizedKey;
    }

    private Instant nowUtc() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static Instant normalizeInstant(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }
}
