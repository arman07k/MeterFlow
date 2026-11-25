package com.meterflow.billing.internal;

import com.meterflow.billing.api.InvoiceStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface InvoiceRepository extends JpaRepository<InvoiceEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM InvoiceEntity i WHERE i.id = :id")
    Optional<InvoiceEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            SELECT i FROM InvoiceEntity i
            WHERE i.subscriptionId = :subscriptionId
              AND i.periodStart = :periodStart
              AND i.periodEnd = :periodEnd
              AND i.status <> com.meterflow.billing.api.InvoiceStatus.VOID
            """)
    Optional<InvoiceEntity> findActiveBySubscriptionAndPeriod(
            @Param("subscriptionId") UUID subscriptionId,
            @Param("periodStart") Instant periodStart,
            @Param("periodEnd") Instant periodEnd
    );

    Optional<InvoiceEntity> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);

    @Query("""
            SELECT i FROM InvoiceEntity i
            WHERE i.subscriptionId = :subscriptionId
              AND i.periodEnd = :periodEnd
              AND i.status <> com.meterflow.billing.api.InvoiceStatus.VOID
            ORDER BY i.createdAt DESC
            """)
    Optional<InvoiceEntity> findActiveBySubscriptionAndPeriodEnd(
            @Param("subscriptionId") UUID subscriptionId,
            @Param("periodEnd") Instant periodEnd
    );

    @Query("""
            SELECT i FROM InvoiceEntity i
            WHERE i.customerId = :customerId
              AND (:status IS NULL OR i.status = :status)
            ORDER BY i.createdAt DESC
            """)
    Page<InvoiceEntity> findCustomerInvoices(
            @Param("customerId") UUID customerId,
            @Param("status") InvoiceStatus status,
            Pageable pageable
    );

    @Query("""
            SELECT i FROM InvoiceEntity i
            WHERE (:status IS NULL OR i.status = :status)
            ORDER BY i.createdAt DESC
            """)
    Page<InvoiceEntity> findAllInvoices(
            @Param("status") InvoiceStatus status,
            Pageable pageable
    );

    @Query("""
            SELECT i FROM InvoiceEntity i
            WHERE i.status = com.meterflow.billing.api.InvoiceStatus.DRAFT
              AND i.graceExpiresAt <= :cutoff
              AND (:customerId IS NULL OR i.customerId = :customerId)
              AND (:afterId IS NULL OR i.id > :afterId)
            ORDER BY i.id ASC
            """)
    Page<InvoiceEntity> findDraftsDueForFinalizationAfter(
            @Param("cutoff") Instant cutoff,
            @Param("customerId") UUID customerId,
            @Param("afterId") UUID afterId,
            Pageable pageable
    );

    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtext(CAST(:subscriptionId AS text)))", nativeQuery = true)
    Integer lockSubscriptionForBilling(@Param("subscriptionId") UUID subscriptionId);

    @Query(value = "SELECT nextval('invoice_number_seq')", nativeQuery = true)
    long nextInvoiceSequenceValue();
}
