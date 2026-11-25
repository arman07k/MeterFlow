package com.meterflow.payments.internal;

import com.meterflow.payments.api.DunningCaseStatus;
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

interface DunningCaseRepository extends JpaRepository<DunningCaseEntity, UUID> {

    Optional<DunningCaseEntity> findByInvoiceId(UUID invoiceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM DunningCaseEntity c WHERE c.invoiceId = :invoiceId")
    Optional<DunningCaseEntity> findByInvoiceIdForUpdate(@Param("invoiceId") UUID invoiceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM DunningCaseEntity c WHERE c.id = :id")
    Optional<DunningCaseEntity> findByIdForUpdate(@Param("id") UUID id);

    Page<DunningCaseEntity> findByCustomerIdOrderByCreatedAtDesc(UUID customerId, Pageable pageable);

    @Query("""
            SELECT c FROM DunningCaseEntity c
            WHERE (:status IS NULL OR c.status = :status)
            ORDER BY c.createdAt DESC
            """)
    Page<DunningCaseEntity> findAllByOptionalStatus(@Param("status") DunningCaseStatus status, Pageable pageable);

    @Query(value = """
            SELECT * FROM dunning_cases
            WHERE status = 'RETRY_SCHEDULED'
              AND next_retry_at IS NOT NULL
              AND next_retry_at <= :referenceTime
            ORDER BY next_retry_at ASC, id ASC
            FOR UPDATE SKIP LOCKED
            LIMIT 1
            """, nativeQuery = true)
    Optional<DunningCaseEntity> claimNextDueRetry(@Param("referenceTime") Instant referenceTime);

    @Query(value = """
            SELECT * FROM dunning_cases
            WHERE status = 'RETRY_SCHEDULED'
              AND next_retry_at IS NOT NULL
              AND next_retry_at <= :referenceTime
              AND id NOT IN (:excludedIds)
            ORDER BY next_retry_at ASC, id ASC
            FOR UPDATE SKIP LOCKED
            LIMIT 1
            """, nativeQuery = true)
    Optional<DunningCaseEntity> claimNextDueRetryExcluding(
            @Param("referenceTime") Instant referenceTime,
            @Param("excludedIds") Collection<UUID> excludedIds
    );

    @Query(value = """
            SELECT * FROM dunning_cases
            WHERE status = 'FINAL_WARNING'
              AND suspension_at IS NOT NULL
              AND suspension_at <= :referenceTime
            ORDER BY suspension_at ASC, id ASC
            FOR UPDATE SKIP LOCKED
            LIMIT 1
            """, nativeQuery = true)
    Optional<DunningCaseEntity> claimNextDueSuspension(@Param("referenceTime") Instant referenceTime);

    @Query(value = """
            SELECT * FROM dunning_cases
            WHERE status = 'FINAL_WARNING'
              AND suspension_at IS NOT NULL
              AND suspension_at <= :referenceTime
              AND id NOT IN (:excludedIds)
            ORDER BY suspension_at ASC, id ASC
            FOR UPDATE SKIP LOCKED
            LIMIT 1
            """, nativeQuery = true)
    Optional<DunningCaseEntity> claimNextDueSuspensionExcluding(
            @Param("referenceTime") Instant referenceTime,
            @Param("excludedIds") Collection<UUID> excludedIds
    );
}
