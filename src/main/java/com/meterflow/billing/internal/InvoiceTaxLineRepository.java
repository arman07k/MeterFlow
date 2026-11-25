package com.meterflow.billing.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface InvoiceTaxLineRepository extends JpaRepository<InvoiceTaxLineEntity, UUID> {

    List<InvoiceTaxLineEntity> findByInvoiceIdOrderByCreatedAtAsc(UUID invoiceId);

    void deleteByInvoiceId(UUID invoiceId);
}
