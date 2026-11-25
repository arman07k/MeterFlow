package com.meterflow.payments.internal;

import com.meterflow.payments.api.PaymentStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PaymentTransactionRepository extends JpaRepository<PaymentTransactionEntity, UUID> {

    Optional<PaymentTransactionEntity> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);

    boolean existsByInvoiceIdAndStatus(UUID invoiceId, PaymentStatus status);

    List<PaymentTransactionEntity> findByInvoiceIdOrderByCreatedAtDesc(UUID invoiceId);

    Page<PaymentTransactionEntity> findByCustomerIdOrderByCreatedAtDesc(UUID customerId, Pageable pageable);

    @Query("""
            SELECT p FROM PaymentTransactionEntity p
            WHERE (:status IS NULL OR p.status = :status)
            ORDER BY p.createdAt DESC
            """)
    Page<PaymentTransactionEntity> findAllByOptionalStatus(@Param("status") PaymentStatus status, Pageable pageable);

    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtext('pay:' || CAST(:invoiceId AS text)))", nativeQuery = true)
    Integer lockInvoiceForPayment(@Param("invoiceId") UUID invoiceId);

    @Query(value = "SELECT nextval('payment_reference_seq')", nativeQuery = true)
    long nextPaymentReferenceSequence();
}
