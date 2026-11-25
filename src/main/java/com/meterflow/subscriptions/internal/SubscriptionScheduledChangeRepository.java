package com.meterflow.subscriptions.internal;

import com.meterflow.subscriptions.api.ScheduledChangeStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SubscriptionScheduledChangeRepository extends JpaRepository<SubscriptionScheduledChangeEntity, UUID> {

    List<SubscriptionScheduledChangeEntity> findBySubscriptionIdOrderByCreatedAtDesc(UUID subscriptionId);

    Optional<SubscriptionScheduledChangeEntity> findBySubscriptionIdAndStatus(
            UUID subscriptionId,
            ScheduledChangeStatus status
    );

    Optional<SubscriptionScheduledChangeEntity> findBySubscriptionIdAndIdempotencyKey(
            UUID subscriptionId,
            String idempotencyKey
    );

    Optional<SubscriptionScheduledChangeEntity> findFirstBySubscriptionIdAndStatusOrderByUpdatedAtDesc(
            UUID subscriptionId,
            ScheduledChangeStatus status
    );

    @Query("""
            SELECT c.id FROM SubscriptionScheduledChangeEntity c
            WHERE c.status = com.meterflow.subscriptions.api.ScheduledChangeStatus.PENDING
              AND c.effectiveAt <= :referenceTime
            ORDER BY c.effectiveAt ASC
            """)
    List<UUID> findIdsDueForExecution(@Param("referenceTime") Instant referenceTime);
}
