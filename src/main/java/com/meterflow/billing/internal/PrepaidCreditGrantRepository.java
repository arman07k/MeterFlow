package com.meterflow.billing.internal;

import com.meterflow.shared.SupportedCurrency;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PrepaidCreditGrantRepository extends JpaRepository<PrepaidCreditGrantEntity, UUID> {

    Optional<PrepaidCreditGrantEntity> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);

    Page<PrepaidCreditGrantEntity> findByCustomerIdOrderByCreatedAtDesc(UUID customerId, Pageable pageable);

    @Query("""
            SELECT g FROM PrepaidCreditGrantEntity g
            WHERE g.customerId = :customerId
              AND g.currency = :currency
              AND g.remainingAmount > 0
              AND (g.expiresAt IS NULL OR g.expiresAt > :now)
            ORDER BY CASE WHEN g.expiresAt IS NULL THEN 1 ELSE 0 END ASC,
                     g.expiresAt ASC,
                     g.priority ASC,
                     g.createdAt ASC,
                     g.id ASC
            """)
    List<PrepaidCreditGrantEntity> findEligibleGrantsReadOnly(
            @Param("customerId") UUID customerId,
            @Param("currency") SupportedCurrency currency,
            @Param("now") Instant now
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT g FROM PrepaidCreditGrantEntity g
            WHERE g.customerId = :customerId
              AND g.currency = :currency
              AND g.remainingAmount > 0
              AND (g.expiresAt IS NULL OR g.expiresAt > :now)
            ORDER BY CASE WHEN g.expiresAt IS NULL THEN 1 ELSE 0 END ASC,
                     g.expiresAt ASC,
                     g.priority ASC,
                     g.createdAt ASC,
                     g.id ASC
            """)
    List<PrepaidCreditGrantEntity> findEligibleGrantsForUpdate(
            @Param("customerId") UUID customerId,
            @Param("currency") SupportedCurrency currency,
            @Param("now") Instant now
    );
}
