package com.meterflow.payments.internal;

import com.meterflow.payments.api.DunningCaseDetail;
import com.meterflow.payments.api.DunningCaseStatus;
import com.meterflow.payments.api.DunningCaseSummary;
import com.meterflow.payments.api.PaymentAttemptRecord;
import com.meterflow.payments.api.PaymentDetail;
import com.meterflow.payments.api.PaymentQueryFacade;
import com.meterflow.payments.api.PaymentResourceNotFoundException;
import com.meterflow.payments.api.PaymentStatus;
import com.meterflow.payments.api.PaymentTransactionSummary;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class PaymentQueryService implements PaymentQueryFacade {

    private final PaymentTransactionRepository transactionRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final DunningCaseRepository dunningCaseRepository;
    private final DunningEventRepository dunningEventRepository;

    PaymentQueryService(
            PaymentTransactionRepository transactionRepository,
            PaymentAttemptRepository attemptRepository,
            DunningCaseRepository dunningCaseRepository,
            DunningEventRepository dunningEventRepository
    ) {
        this.transactionRepository = transactionRepository;
        this.attemptRepository = attemptRepository;
        this.dunningCaseRepository = dunningCaseRepository;
        this.dunningEventRepository = dunningEventRepository;
    }

    @Override
    public Optional<PaymentDetail> findPaymentById(UUID paymentId) {
        return transactionRepository.findById(paymentId)
                .map(tx -> PaymentMapper.toPaymentDetail(
                        tx,
                        attemptRepository.findByPaymentTransactionIdOrderByAttemptedAtAsc(tx.getId())
                ));
    }

    @Override
    public List<PaymentAttemptRecord> listPaymentAttempts(UUID paymentId) {
        if (!transactionRepository.existsById(paymentId)) {
            throw new PaymentResourceNotFoundException("Payment not found: " + paymentId);
        }
        return attemptRepository.findByPaymentTransactionIdOrderByAttemptedAtAsc(paymentId)
                .stream()
                .map(PaymentMapper::toAttemptRecord)
                .toList();
    }

    @Override
    public List<PaymentTransactionSummary> listPaymentsForInvoice(UUID invoiceId) {
        return transactionRepository.findByInvoiceIdOrderByCreatedAtDesc(invoiceId)
                .stream()
                .map(PaymentMapper::toTransactionSummary)
                .toList();
    }

    @Override
    public Page<PaymentTransactionSummary> listCustomerPayments(UUID customerId, Pageable pageable) {
        return transactionRepository.findByCustomerIdOrderByCreatedAtDesc(customerId, pageable)
                .map(PaymentMapper::toTransactionSummary);
    }

    @Override
    public Page<PaymentTransactionSummary> listAllPayments(PaymentStatus status, Pageable pageable) {
        return transactionRepository.findAllByOptionalStatus(status, pageable)
                .map(PaymentMapper::toTransactionSummary);
    }

    @Override
    public Optional<DunningCaseDetail> findDunningCaseById(UUID dunningCaseId) {
        return dunningCaseRepository.findById(dunningCaseId).map(this::loadCaseDetail);
    }

    @Override
    public Optional<DunningCaseDetail> findDunningCaseByInvoiceId(UUID invoiceId) {
        return dunningCaseRepository.findByInvoiceId(invoiceId).map(this::loadCaseDetail);
    }

    @Override
    public Page<DunningCaseSummary> listCustomerDunningCases(UUID customerId, Pageable pageable) {
        return dunningCaseRepository.findByCustomerIdOrderByCreatedAtDesc(customerId, pageable)
                .map(PaymentMapper::toCaseSummary);
    }

    @Override
    public Page<DunningCaseSummary> listDunningCases(DunningCaseStatus status, Pageable pageable) {
        return dunningCaseRepository.findAllByOptionalStatus(status, pageable)
                .map(PaymentMapper::toCaseSummary);
    }

    private DunningCaseDetail loadCaseDetail(DunningCaseEntity dunningCase) {
        List<DunningEventEntity> events =
                dunningEventRepository.findByDunningCaseIdOrderByOccurredAtAsc(dunningCase.getId());
        List<PaymentAttemptEntity> attempts =
                attemptRepository.findByDunningCaseIdOrderByAttemptedAtAsc(dunningCase.getId());
        return PaymentMapper.toCaseDetail(dunningCase, events, attempts);
    }
}
