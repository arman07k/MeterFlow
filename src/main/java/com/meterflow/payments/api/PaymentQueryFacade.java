package com.meterflow.payments.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface PaymentQueryFacade {

    Optional<PaymentDetail> findPaymentById(UUID paymentId);

    List<PaymentAttemptRecord> listPaymentAttempts(UUID paymentId);

    List<PaymentTransactionSummary> listPaymentsForInvoice(UUID invoiceId);

    Page<PaymentTransactionSummary> listCustomerPayments(UUID customerId, Pageable pageable);

    Page<PaymentTransactionSummary> listAllPayments(PaymentStatus status, Pageable pageable);

    Optional<DunningCaseDetail> findDunningCaseById(UUID dunningCaseId);

    Optional<DunningCaseDetail> findDunningCaseByInvoiceId(UUID invoiceId);

    Page<DunningCaseSummary> listCustomerDunningCases(UUID customerId, Pageable pageable);

    Page<DunningCaseSummary> listDunningCases(DunningCaseStatus status, Pageable pageable);
}
