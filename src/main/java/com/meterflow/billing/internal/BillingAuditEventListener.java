package com.meterflow.billing.internal;

import com.meterflow.billing.api.CreditNoteIssuedEvent;
import com.meterflow.billing.api.InvoiceFinalizedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Durable Spring Modulith outbox listener recording structured audit entries for finalized invoices
 * and issued credit notes (FR5, FR6, ADR-002, ADR-014). Ensures transactional persistence in
 * {@code event_publication} without introducing payment or outbound webhook delivery.
 */
@Component
class BillingAuditEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(BillingAuditEventListener.class);

    @ApplicationModuleListener
    void onInvoiceFinalized(InvoiceFinalizedEvent event) {
        LOGGER.debug(
                "Recorded finalized invoice outbox event: invoiceId={} invoiceNumber={} total={} due={}",
                event.invoiceId(),
                event.invoiceNumber(),
                event.totalAmount(),
                event.payableAmountDue()
        );
    }

    @ApplicationModuleListener
    void onCreditNoteIssued(CreditNoteIssuedEvent event) {
        LOGGER.debug(
                "Recorded credit note outbox event: creditNoteId={} creditNoteNumber={} invoiceId={} totalCredit={}",
                event.creditNoteId(),
                event.creditNoteNumber(),
                event.originalInvoiceId(),
                event.totalCredit()
        );
    }
}
