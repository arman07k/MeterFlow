package com.meterflow.billing.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface InvoiceLineRepository extends JpaRepository<InvoiceLineEntity, UUID> {

    List<InvoiceLineEntity> findByInvoiceIdOrderByLineOrderAsc(UUID invoiceId);

    void deleteByInvoiceId(UUID invoiceId);
}
