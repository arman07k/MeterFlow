package com.meterflow.subscriptions.internal;

import com.meterflow.subscriptions.api.SubscriptionStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SubscriptionRepository extends JpaRepository<SubscriptionEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SubscriptionEntity s WHERE s.id = :id")
    Optional<SubscriptionEntity> findByIdForUpdate(@Param("id") UUID id);

    Optional<SubscriptionEntity> findByCustomerIdAndCreationIdempotencyKey(
            UUID customerId,
            String creationIdempotencyKey
    );

    boolean existsByCustomerIdAndStatusIn(UUID customerId, Collection<SubscriptionStatus> statuses);

    Optional<SubscriptionEntity> findFirstByCustomerIdAndStatusInOrderByCreatedAtDesc(
            UUID customerId,
            Collection<SubscriptionStatus> statuses
    );

    List<SubscriptionEntity> findByCustomerIdInAndStatusIn(
            Collection<UUID> customerIds,
            Collection<SubscriptionStatus> statuses
    );

    Page<SubscriptionEntity> findByCustomerIdOrderByCreatedAtDesc(UUID customerId, Pageable pageable);

    Page<SubscriptionEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<SubscriptionEntity> findByStatusOrderByCreatedAtDesc(SubscriptionStatus status, Pageable pageable);

    long countByStatus(SubscriptionStatus status);

    @Query("""
            SELECT s FROM SubscriptionEntity s
            WHERE s.status IN :statuses
              AND s.currentPeriodStart IS NOT NULL
              AND s.currentPeriodEnd IS NOT NULL
            ORDER BY s.id ASC
            """)
    Page<SubscriptionEntity> findBillableSubscriptions(
            @Param("statuses") Collection<SubscriptionStatus> statuses,
            Pageable pageable
    );

    @Query("""
            SELECT s FROM SubscriptionEntity s
            WHERE s.status IN :statuses
              AND s.currentPeriodStart IS NOT NULL
              AND s.currentPeriodEnd IS NOT NULL
            ORDER BY s.id ASC
            """)
    List<SubscriptionEntity> findFirstBillableBatch(
            @Param("statuses") Collection<SubscriptionStatus> statuses,
            Pageable pageable
    );

    @Query("""
            SELECT s FROM SubscriptionEntity s
            WHERE s.status IN :statuses
              AND s.currentPeriodStart IS NOT NULL
              AND s.currentPeriodEnd IS NOT NULL
              AND s.id > :lastSeenId
            ORDER BY s.id ASC
            """)
    List<SubscriptionEntity> findBillableBatchAfter(
            @Param("statuses") Collection<SubscriptionStatus> statuses,
            @Param("lastSeenId") UUID lastSeenId,
            Pageable pageable
    );

    @Query("""
            SELECT s.id FROM SubscriptionEntity s
            WHERE s.status = com.meterflow.subscriptions.api.SubscriptionStatus.TRIALING
              AND s.trialEndAt IS NOT NULL
              AND s.trialEndAt <= :referenceTime
            ORDER BY s.trialEndAt ASC
            """)
    List<UUID> findIdsDueForTrialProcessing(@Param("referenceTime") Instant referenceTime);

    @Query("""
            SELECT s.id FROM SubscriptionEntity s
            WHERE s.status = com.meterflow.subscriptions.api.SubscriptionStatus.ACTIVE
              AND s.cancelAtPeriodEnd = true
              AND s.currentPeriodEnd IS NOT NULL
              AND s.currentPeriodEnd <= :referenceTime
            ORDER BY s.currentPeriodEnd ASC
            """)
    List<UUID> findIdsDueForPeriodEndCancellation(@Param("referenceTime") Instant referenceTime);

    /**
     * Acquires a PostgreSQL transaction-scoped advisory lock per customer to serialize concurrent
     * subscription creation attempts for the same customer alongside the DB partial unique index.
     */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtext(CAST(:customerId AS text)))", nativeQuery = true)
    Integer lockCustomerForSubscriptionCreation(@Param("customerId") UUID customerId);
}
