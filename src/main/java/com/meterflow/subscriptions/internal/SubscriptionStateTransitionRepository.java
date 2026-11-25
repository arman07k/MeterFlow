package com.meterflow.subscriptions.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SubscriptionStateTransitionRepository extends JpaRepository<SubscriptionStateTransitionEntity, UUID> {

    List<SubscriptionStateTransitionEntity> findBySubscriptionIdOrderByCreatedAtAsc(UUID subscriptionId);

    Optional<SubscriptionStateTransitionEntity> findBySubscriptionIdAndIdempotencyKey(
            UUID subscriptionId,
            String idempotencyKey
    );
}
