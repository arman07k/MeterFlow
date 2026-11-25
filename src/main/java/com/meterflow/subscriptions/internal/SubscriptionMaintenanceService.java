package com.meterflow.subscriptions.internal;

import com.meterflow.catalog.api.PlanCatalogFacade;
import com.meterflow.catalog.api.PlanVersionDetail;
import com.meterflow.catalog.api.PlanVersionStatus;
import com.meterflow.shared.BillingPeriod;
import com.meterflow.subscriptions.api.MaintenanceProcessingSummary;
import com.meterflow.subscriptions.api.ScheduledChangeStatus;
import com.meterflow.subscriptions.api.SubscriptionMaintenanceFacade;
import com.meterflow.subscriptions.api.SubscriptionStatus;
import com.meterflow.subscriptions.api.TrialConversionPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Repeatable, restart-safe, per-record locked maintenance executor for due trial transitions,
 * period-end cancellations, and scheduled plan changes (FR4, ADR-012).
 */
@Service
class SubscriptionMaintenanceService implements SubscriptionMaintenanceFacade {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubscriptionMaintenanceService.class);

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionPlanAssignmentRepository assignmentRepository;
    private final SubscriptionScheduledChangeRepository scheduledChangeRepository;
    private final SubscriptionStateTransitionRepository transitionRepository;
    private final PlanCatalogFacade planCatalogFacade;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    SubscriptionMaintenanceService(
            SubscriptionRepository subscriptionRepository,
            SubscriptionPlanAssignmentRepository assignmentRepository,
            SubscriptionScheduledChangeRepository scheduledChangeRepository,
            SubscriptionStateTransitionRepository transitionRepository,
            PlanCatalogFacade planCatalogFacade,
            PlatformTransactionManager transactionManager,
            Clock clock
    ) {
        this.subscriptionRepository = subscriptionRepository;
        this.assignmentRepository = assignmentRepository;
        this.scheduledChangeRepository = scheduledChangeRepository;
        this.transitionRepository = transitionRepository;
        this.planCatalogFacade = planCatalogFacade;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public MaintenanceProcessingSummary processDueOperations() {
        return processDueOperationsAt(clock.instant());
    }

    @Override
    public MaintenanceProcessingSummary processDueOperationsAt(Instant referenceTimeUtc) {
        Instant refTime = (referenceTimeUtc != null ? referenceTimeUtc : clock.instant())
                .truncatedTo(ChronoUnit.MICROS);

        int trialsConverted = 0;
        int trialsExpired = 0;
        int periodEndCancelled = 0;
        int scheduledChangesApplied = 0;

        List<UUID> dueTrialIds = subscriptionRepository.findIdsDueForTrialProcessing(refTime);
        for (UUID subscriptionId : dueTrialIds) {
            TrialOutcome outcome = processSingleDueTrial(subscriptionId, refTime);
            if (outcome == TrialOutcome.CONVERTED) {
                trialsConverted++;
            } else if (outcome == TrialOutcome.EXPIRED) {
                trialsExpired++;
            }
        }

        List<UUID> dueCancelIds = subscriptionRepository.findIdsDueForPeriodEndCancellation(refTime);
        for (UUID subscriptionId : dueCancelIds) {
            if (processSinglePeriodEndCancellation(subscriptionId, refTime)) {
                periodEndCancelled++;
            }
        }

        List<UUID> dueScheduledChangeIds = scheduledChangeRepository.findIdsDueForExecution(refTime);
        for (UUID scheduledChangeId : dueScheduledChangeIds) {
            if (processSingleScheduledChange(scheduledChangeId, refTime)) {
                scheduledChangesApplied++;
            }
        }

        if (trialsConverted + trialsExpired + periodEndCancelled + scheduledChangesApplied > 0) {
            LOGGER.info(
                    "Subscription maintenance processed: trialsConverted={}, trialsExpired={}, scheduledChangesApplied={}, periodEndCancellationsApplied={}",
                    trialsConverted,
                    trialsExpired,
                    scheduledChangesApplied,
                    periodEndCancelled
            );
        }

        return new MaintenanceProcessingSummary(
                trialsConverted,
                trialsExpired,
                scheduledChangesApplied,
                periodEndCancelled
        );
    }

    private TrialOutcome processSingleDueTrial(UUID subscriptionId, Instant refTime) {
        return transactionTemplate.execute(status -> {
            Optional<SubscriptionEntity> locked = subscriptionRepository.findByIdForUpdate(subscriptionId);
            if (locked.isEmpty()) {
                return TrialOutcome.NOOP;
            }
            SubscriptionEntity sub = locked.get();
            if (sub.getStatus() != SubscriptionStatus.TRIALING
                    || sub.getTrialEndAt() == null
                    || sub.getTrialEndAt().isAfter(refTime)) {
                return TrialOutcome.NOOP;
            }

            Instant boundary = sub.getTrialEndAt();
            if (sub.getTrialConversionPolicy() == TrialConversionPolicy.AUTO_CONVERT_TO_PAID) {
                ZoneId zone = BillingPeriod.parseTimezone(sub.getBillingTimezone());
                BillingPeriod period = BillingPeriod.startingAt(boundary, sub.getBillingInterval(), zone);
                closeCurrentAssignment(subscriptionId, boundary);
                assignmentRepository.save(new SubscriptionPlanAssignmentEntity(
                        subscriptionId,
                        sub.getCurrentPlanVersionId(),
                        sub.getSeatQuantity(),
                        period.start(),
                        null,
                        "TRIAL_AUTO_CONVERSION",
                        refTime
                ));
                sub.activateFromTrial(period.start(), period.end(), refTime);
                subscriptionRepository.save(sub);

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("planVersionId", sub.getCurrentPlanVersionId().toString());
                metadata.put("periodStart", period.start().toString());
                metadata.put("periodEnd", period.end().toString());
                transitionRepository.save(new SubscriptionStateTransitionEntity(
                        subscriptionId,
                        SubscriptionStatus.TRIALING,
                        SubscriptionStatus.ACTIVE,
                        "TRIAL_AUTO_CONVERTED",
                        boundary,
                        null,
                        metadata,
                        refTime
                ));
                return TrialOutcome.CONVERTED;
            }

            closeCurrentAssignment(subscriptionId, boundary);
            sub.expireTrial(refTime);
            subscriptionRepository.save(sub);
            transitionRepository.save(new SubscriptionStateTransitionEntity(
                    subscriptionId,
                    SubscriptionStatus.TRIALING,
                    SubscriptionStatus.EXPIRED,
                    "TRIAL_EXPIRED",
                    boundary,
                    null,
                    Map.of("trialEndAt", boundary.toString()),
                    refTime
            ));
            return TrialOutcome.EXPIRED;
        });
    }

    private boolean processSinglePeriodEndCancellation(UUID subscriptionId, Instant refTime) {
        Boolean applied = transactionTemplate.execute(status -> {
            Optional<SubscriptionEntity> locked = subscriptionRepository.findByIdForUpdate(subscriptionId);
            if (locked.isEmpty()) {
                return false;
            }
            SubscriptionEntity sub = locked.get();
            if (sub.getStatus() != SubscriptionStatus.ACTIVE
                    || !sub.isCancelAtPeriodEnd()
                    || sub.getCurrentPeriodEnd() == null
                    || sub.getCurrentPeriodEnd().isAfter(refTime)) {
                return false;
            }

            Instant boundary = sub.getCurrentPeriodEnd();
            scheduledChangeRepository.findBySubscriptionIdAndStatus(subscriptionId, ScheduledChangeStatus.PENDING)
                    .ifPresent(pending -> {
                        pending.markCancelled(refTime);
                        scheduledChangeRepository.save(pending);
                    });

            closeCurrentAssignment(subscriptionId, boundary);
            sub.cancelNow(boundary, refTime);
            subscriptionRepository.save(sub);

            transitionRepository.save(new SubscriptionStateTransitionEntity(
                    subscriptionId,
                    SubscriptionStatus.ACTIVE,
                    SubscriptionStatus.CANCELLED,
                    "CANCELLED_AT_PERIOD_END",
                    boundary,
                    null,
                    Map.of("periodEnd", boundary.toString()),
                    refTime
            ));
            return true;
        });
        return Boolean.TRUE.equals(applied);
    }

    private boolean processSingleScheduledChange(UUID scheduledChangeId, Instant refTime) {
        Boolean applied = transactionTemplate.execute(status -> {
            Optional<SubscriptionScheduledChangeEntity> found = scheduledChangeRepository.findById(scheduledChangeId);
            if (found.isEmpty()) {
                return false;
            }
            SubscriptionScheduledChangeEntity change = found.get();
            if (change.getStatus() != ScheduledChangeStatus.PENDING || change.getEffectiveAt().isAfter(refTime)) {
                return false;
            }

            Optional<SubscriptionEntity> lockedSub = subscriptionRepository.findByIdForUpdate(change.getSubscriptionId());
            if (lockedSub.isEmpty()) {
                return false;
            }
            SubscriptionEntity sub = lockedSub.get();

            // Re-read scheduled change inside subscription lock to guarantee single execution
            change = scheduledChangeRepository.findById(scheduledChangeId).orElse(null);
            if (change == null || change.getStatus() != ScheduledChangeStatus.PENDING) {
                return false;
            }

            if (sub.getStatus() != SubscriptionStatus.ACTIVE || sub.isCancelAtPeriodEnd()) {
                change.markCancelled(refTime);
                scheduledChangeRepository.save(change);
                return false;
            }

            Optional<PlanVersionDetail> targetVersion = planCatalogFacade
                    .findPlanVersionById(change.getTargetPlanVersionId());
            if (targetVersion.isEmpty() || targetVersion.get().status() != PlanVersionStatus.PUBLISHED) {
                LOGGER.warn(
                        "Skipping scheduled plan change {} because target plan version {} is not PUBLISHED",
                        scheduledChangeId,
                        change.getTargetPlanVersionId()
                );
                return false;
            }

            Instant boundary = change.getEffectiveAt();
            ZoneId zone = BillingPeriod.parseTimezone(sub.getBillingTimezone());
            BillingPeriod nextPeriod = BillingPeriod.startingAt(boundary, sub.getBillingInterval(), zone);

            UUID previousPlanVersionId = sub.getCurrentPlanVersionId();
            closeCurrentAssignment(sub.getId(), boundary);
            assignmentRepository.save(new SubscriptionPlanAssignmentEntity(
                    sub.getId(),
                    change.getTargetPlanVersionId(),
                    change.getTargetSeatQuantity(),
                    nextPeriod.start(),
                    null,
                    "SCHEDULED_CHANGE_APPLIED",
                    refTime
            ));

            sub.applyScheduledPlanChange(
                    change.getTargetPlanVersionId(),
                    change.getTargetSeatQuantity(),
                    nextPeriod.start(),
                    nextPeriod.end(),
                    refTime
            );
            subscriptionRepository.save(sub);

            change.markApplied(refTime);
            scheduledChangeRepository.save(change);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("scheduledChangeId", change.getId().toString());
            metadata.put("previousPlanVersionId", previousPlanVersionId.toString());
            metadata.put("newPlanVersionId", change.getTargetPlanVersionId().toString());
            metadata.put("newSeatQuantity", change.getTargetSeatQuantity());
            metadata.put("direction", change.getChangeDirection().name());
            metadata.put("nextPeriodStart", nextPeriod.start().toString());
            metadata.put("nextPeriodEnd", nextPeriod.end().toString());

            transitionRepository.save(new SubscriptionStateTransitionEntity(
                    sub.getId(),
                    SubscriptionStatus.ACTIVE,
                    SubscriptionStatus.ACTIVE,
                    "SCHEDULED_PLAN_CHANGE_APPLIED",
                    boundary,
                    null,
                    metadata,
                    refTime
            ));
            return true;
        });
        return Boolean.TRUE.equals(applied);
    }

    private void closeCurrentAssignment(UUID subscriptionId, Instant effectiveTo) {
        assignmentRepository
                .findFirstBySubscriptionIdAndEffectiveToIsNullOrderByEffectiveFromDesc(subscriptionId)
                .ifPresent(active -> {
                    active.closeAt(effectiveTo);
                    assignmentRepository.save(active);
                });
    }

    private enum TrialOutcome {
        CONVERTED,
        EXPIRED,
        NOOP
    }
}
