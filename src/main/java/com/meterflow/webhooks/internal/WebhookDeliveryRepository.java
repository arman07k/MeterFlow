package com.meterflow.webhooks.internal;

import com.meterflow.webhooks.api.WebhookDeliveryStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface WebhookDeliveryRepository extends JpaRepository<WebhookDeliveryEntity, UUID> {

    boolean existsByWebhookEndpointIdAndSourceEventId(UUID webhookEndpointId, UUID sourceEventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM WebhookDeliveryEntity d WHERE d.id = :id")
    Optional<WebhookDeliveryEntity> findByIdForUpdate(@Param("id") UUID id);

    Page<WebhookDeliveryEntity> findByCustomerIdOrderByCreatedAtDesc(UUID customerId, Pageable pageable);

    Page<WebhookDeliveryEntity> findByCustomerIdAndStatusOrderByCreatedAtDesc(
            UUID customerId,
            WebhookDeliveryStatus status,
            Pageable pageable
    );

    Page<WebhookDeliveryEntity> findByWebhookEndpointIdOrderByCreatedAtDesc(
            UUID webhookEndpointId,
            Pageable pageable
    );

    Page<WebhookDeliveryEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<WebhookDeliveryEntity> findByStatusOrderByCreatedAtDesc(WebhookDeliveryStatus status, Pageable pageable);

    long countByStatus(WebhookDeliveryStatus status);

    @Query(value = """
            SELECT * FROM webhook_deliveries
            WHERE status IN ('PENDING', 'RETRYING')
              AND next_attempt_at IS NOT NULL
              AND next_attempt_at <= :referenceTime
            ORDER BY next_attempt_at ASC, id ASC
            FOR UPDATE SKIP LOCKED
            LIMIT 1
            """, nativeQuery = true)
    Optional<WebhookDeliveryEntity> claimNextDue(@Param("referenceTime") Instant referenceTime);

    @Query(value = """
            SELECT * FROM webhook_deliveries
            WHERE status IN ('PENDING', 'RETRYING')
              AND next_attempt_at IS NOT NULL
              AND next_attempt_at <= :referenceTime
              AND id NOT IN (:excludedIds)
            ORDER BY next_attempt_at ASC, id ASC
            FOR UPDATE SKIP LOCKED
            LIMIT 1
            """, nativeQuery = true)
    Optional<WebhookDeliveryEntity> claimNextDueExcluding(
            @Param("referenceTime") Instant referenceTime,
            @Param("excludedIds") Collection<UUID> excludedIds
    );
}
