package com.meterflow.billing.api;

import com.meterflow.shared.Money;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface InvoiceManagementFacade {

    InvoiceDetail generateOrRefreshDraft(GenerateDraftInvoiceCommand command);

    InvoiceDetail finalizeInvoice(UUID invoiceId, FinalizeInvoiceCommand command);

    InvoiceDetail voidInvoice(UUID invoiceId, VoidInvoiceCommand command);

    InvoiceSummary recordPaymentSettlement(
            UUID invoiceId,
            Money settledAmount,
            String paymentReference,
            String idempotencyKey
    );

    InvoiceSummary markInvoicePastDue(UUID invoiceId, String reason);

    Optional<InvoiceSummary> findInvoiceById(UUID invoiceId);

    InvoiceDetail getInvoiceDetail(UUID invoiceId);

    Page<InvoiceSummary> listCustomerInvoices(UUID customerId, InvoiceStatus status, Pageable pageable);

    Page<InvoiceSummary> listInvoices(InvoiceStatus status, Pageable pageable);
}
