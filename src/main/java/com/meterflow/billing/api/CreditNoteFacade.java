package com.meterflow.billing.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface CreditNoteFacade {

    CreditNoteDetail issueCreditNote(IssueCreditNoteCommand command);

    Optional<CreditNoteDetail> findCreditNoteById(UUID creditNoteId);

    List<CreditNoteDetail> listCreditNotesForInvoice(UUID invoiceId);

    Page<CreditNoteDetail> listCustomerCreditNotes(UUID customerId, Pageable pageable);
}
