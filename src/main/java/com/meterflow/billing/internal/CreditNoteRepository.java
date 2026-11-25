package com.meterflow.billing.internal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CreditNoteRepository extends JpaRepository<CreditNoteEntity, UUID> {

    Optional<CreditNoteEntity> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);

    List<CreditNoteEntity> findByOriginalInvoiceIdOrderByIssuedAtDesc(UUID originalInvoiceId);

    Page<CreditNoteEntity> findByCustomerIdOrderByIssuedAtDesc(UUID customerId, Pageable pageable);

    @Query("SELECT COALESCE(SUM(c.totalCredit), 0) FROM CreditNoteEntity c WHERE c.originalInvoiceId = :invoiceId")
    BigDecimal sumTotalCreditByInvoiceId(@Param("invoiceId") UUID invoiceId);

    @Query(value = "SELECT nextval('credit_note_number_seq')", nativeQuery = true)
    long nextCreditNoteSequenceValue();
}
