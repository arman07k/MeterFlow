package com.meterflow.subscriptions.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SubscriptionPlanAssignmentRepository extends JpaRepository<SubscriptionPlanAssignmentEntity, UUID> {

    List<SubscriptionPlanAssignmentEntity> findBySubscriptionIdOrderByEffectiveFromAsc(UUID subscriptionId);

    Optional<SubscriptionPlanAssignmentEntity> findFirstBySubscriptionIdAndEffectiveToIsNullOrderByEffectiveFromDesc(
            UUID subscriptionId
    );
}
