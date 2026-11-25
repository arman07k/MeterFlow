package com.meterflow.subscriptions.internal;

import com.meterflow.subscriptions.api.ProrationRecord;
import com.meterflow.subscriptions.api.ScheduledPlanChangeRecord;
import com.meterflow.subscriptions.api.SubscriptionHistory;
import com.meterflow.subscriptions.api.SubscriptionNotFoundException;
import com.meterflow.subscriptions.api.SubscriptionPlanAssignmentRecord;
import com.meterflow.subscriptions.api.SubscriptionQueryFacade;
import com.meterflow.subscriptions.api.SubscriptionStatus;
import com.meterflow.subscriptions.api.SubscriptionSummary;
import com.meterflow.subscriptions.api.SubscriptionTransitionRecord;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class SubscriptionQueryService implements SubscriptionQueryFacade {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionPlanAssignmentRepository assignmentRepository;
    private final SubscriptionScheduledChangeRepository scheduledChangeRepository;
    private final SubscriptionProrationRecordRepository prorationRepository;
    private final SubscriptionStateTransitionRepository transitionRepository;

    SubscriptionQueryService(
            SubscriptionRepository subscriptionRepository,
            SubscriptionPlanAssignmentRepository assignmentRepository,
            SubscriptionScheduledChangeRepository scheduledChangeRepository,
            SubscriptionProrationRecordRepository prorationRepository,
            SubscriptionStateTransitionRepository transitionRepository
    ) {
        this.subscriptionRepository = subscriptionRepository;
        this.assignmentRepository = assignmentRepository;
        this.scheduledChangeRepository = scheduledChangeRepository;
        this.prorationRepository = prorationRepository;
        this.transitionRepository = transitionRepository;
    }

    private static final Set<SubscriptionStatus> OPEN_STATUSES = EnumSet.of(
            SubscriptionStatus.TRIALING,
            SubscriptionStatus.ACTIVE,
            SubscriptionStatus.PAUSED,
            SubscriptionStatus.PAST_DUE,
            SubscriptionStatus.SUSPENDED
    );

    @Override
    public Optional<SubscriptionSummary> findSubscriptionById(UUID subscriptionId) {
        return subscriptionRepository.findById(subscriptionId).map(SubscriptionMapper::toSummary);
    }

    @Override
    public Optional<SubscriptionSummary> findOpenSubscriptionByCustomerId(UUID customerId) {
        if (customerId == null) {
            return Optional.empty();
        }
        return subscriptionRepository
                .findFirstByCustomerIdAndStatusInOrderByCreatedAtDesc(customerId, OPEN_STATUSES)
                .map(SubscriptionMapper::toSummary);
    }

    @Override
    public Map<UUID, SubscriptionSummary> findOpenSubscriptionsByCustomerIds(Collection<UUID> customerIds) {
        if (customerIds == null || customerIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, SubscriptionSummary> result = new HashMap<>();
        for (SubscriptionEntity entity : subscriptionRepository.findByCustomerIdInAndStatusIn(customerIds, OPEN_STATUSES)) {
            result.put(entity.getCustomerId(), SubscriptionMapper.toSummary(entity));
        }
        return Map.copyOf(result);
    }

    private static final Set<SubscriptionStatus> BILLABLE_STATUSES = EnumSet.of(
            SubscriptionStatus.ACTIVE,
            SubscriptionStatus.PAST_DUE,
            SubscriptionStatus.PAUSED
    );

    @Override
    public Page<SubscriptionSummary> listCustomerSubscriptions(UUID customerId, Pageable pageable) {
        return subscriptionRepository
                .findByCustomerIdOrderByCreatedAtDesc(customerId, pageable)
                .map(SubscriptionMapper::toSummary);
    }

    @Override
    public Page<SubscriptionSummary> listSubscriptions(SubscriptionStatus status, Pageable pageable) {
        if (status == null) {
            return subscriptionRepository
                    .findAllByOrderByCreatedAtDesc(pageable)
                    .map(SubscriptionMapper::toSummary);
        }
        return subscriptionRepository
                .findByStatusOrderByCreatedAtDesc(status, pageable)
                .map(SubscriptionMapper::toSummary);
    }

    @Override
    public Map<SubscriptionStatus, Long> countSubscriptionsByStatus() {
        Map<SubscriptionStatus, Long> counts = new HashMap<>();
        for (SubscriptionStatus status : SubscriptionStatus.values()) {
            counts.put(status, subscriptionRepository.countByStatus(status));
        }
        return Map.copyOf(counts);
    }

    @Override
    public Page<SubscriptionSummary> listBillableSubscriptions(Pageable pageable) {
        return subscriptionRepository
                .findBillableSubscriptions(BILLABLE_STATUSES, pageable)
                .map(SubscriptionMapper::toSummary);
    }

    @Override
    public List<SubscriptionSummary> listBillableSubscriptionsAfter(UUID lastSeenId, int limit) {
        int safeLimit = Math.clamp(limit, 1, 500);
        Pageable page = PageRequest.of(0, safeLimit);
        List<SubscriptionEntity> entities = lastSeenId == null
                ? subscriptionRepository.findFirstBillableBatch(BILLABLE_STATUSES, page)
                : subscriptionRepository.findBillableBatchAfter(BILLABLE_STATUSES, lastSeenId, page);
        return entities.stream().map(SubscriptionMapper::toSummary).toList();
    }

    @Override
    public List<ProrationRecord> listProrationRecords(UUID subscriptionId) {
        if (subscriptionId == null) {
            return List.of();
        }
        return prorationRepository
                .findBySubscriptionIdOrderByCreatedAtDesc(subscriptionId)
                .stream()
                .map(SubscriptionMapper::toProrationRecord)
                .toList();
    }

    @Override
    public SubscriptionHistory getSubscriptionHistory(UUID subscriptionId) {
        SubscriptionEntity subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new SubscriptionNotFoundException("Subscription not found"));

        List<SubscriptionPlanAssignmentRecord> assignments = assignmentRepository
                .findBySubscriptionIdOrderByEffectiveFromAsc(subscriptionId)
                .stream()
                .map(SubscriptionMapper::toAssignmentRecord)
                .toList();

        List<ScheduledPlanChangeRecord> scheduledChanges = scheduledChangeRepository
                .findBySubscriptionIdOrderByCreatedAtDesc(subscriptionId)
                .stream()
                .map(SubscriptionMapper::toScheduledChangeRecord)
                .toList();

        List<ProrationRecord> prorationRecords = prorationRepository
                .findBySubscriptionIdOrderByCreatedAtDesc(subscriptionId)
                .stream()
                .map(SubscriptionMapper::toProrationRecord)
                .toList();

        List<SubscriptionTransitionRecord> transitions = transitionRepository
                .findBySubscriptionIdOrderByCreatedAtAsc(subscriptionId)
                .stream()
                .map(SubscriptionMapper::toTransitionRecord)
                .toList();

        return new SubscriptionHistory(
                SubscriptionMapper.toSummary(subscription),
                assignments,
                scheduledChanges,
                prorationRecords,
                transitions
        );
    }
}
