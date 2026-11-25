package com.meterflow.billing.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CreditNoteLineRepository extends JpaRepository<CreditNoteLineEntity, UUID> {

    List<CreditNoteLineEntity> findByCreditNoteIdOrderByCreatedAtAsc(UUID creditNoteId);
}
