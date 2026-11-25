package com.meterflow.subscriptions.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SubscriptionProrationRecordRepository extends JpaRepository<SubscriptionProrationRecordEntity, UUID> {

    List<SubscriptionProrationRecordEntity> findBySubscriptionIdOrderByCreatedAtDesc(UUID subscriptionId);

    Optional<SubscriptionProrationRecordEntity> findBySubscriptionIdAndIdempotencyKey(
            UUID subscriptionId,
            String idempotencyKey
    );
}
