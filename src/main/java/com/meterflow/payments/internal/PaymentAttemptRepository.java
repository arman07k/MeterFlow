package com.meterflow.payments.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PaymentAttemptRepository extends JpaRepository<PaymentAttemptEntity, UUID> {

    List<PaymentAttemptEntity> findByPaymentTransactionIdOrderByAttemptedAtAsc(UUID paymentTransactionId);

    List<PaymentAttemptEntity> findByInvoiceIdOrderByAttemptedAtAsc(UUID invoiceId);

    List<PaymentAttemptEntity> findByDunningCaseIdOrderByAttemptedAtAsc(UUID dunningCaseId);
}
