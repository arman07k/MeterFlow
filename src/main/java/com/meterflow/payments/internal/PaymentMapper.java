package com.meterflow.payments.internal;

import com.meterflow.payments.api.DunningCaseDetail;
import com.meterflow.payments.api.DunningCaseSummary;
import com.meterflow.payments.api.DunningEventRecord;
import com.meterflow.payments.api.DunningPolicySummary;
import com.meterflow.payments.api.PaymentAttemptRecord;
import com.meterflow.payments.api.PaymentDetail;
import com.meterflow.payments.api.PaymentTransactionSummary;
import com.meterflow.shared.Money;
import java.util.List;

final class PaymentMapper {

    private PaymentMapper() {
    }

    static PaymentTransactionSummary toTransactionSummary(PaymentTransactionEntity e) {
        return new PaymentTransactionSummary(
                e.getId(),
                e.getPaymentReference(),
                e.getInvoiceId(),
                e.getCustomerId(),
                e.getSubscriptionId(),
                e.getDunningCaseId(),
                e.getCurrency(),
                Money.of(e.getAmount(), e.getCurrency()),
                e.getStatus(),
                e.getAttemptSource(),
                e.getAttemptNumber(),
                e.getSimulationOutcome(),
                e.isRetryable(),
                e.getFailureCode(),
                e.getFailureReason(),
                e.getIdempotencyKey(),
                e.getCompletedAt(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }

    static PaymentAttemptRecord toAttemptRecord(PaymentAttemptEntity e) {
        return new PaymentAttemptRecord(
                e.getId(),
                e.getPaymentTransactionId(),
                e.getInvoiceId(),
                e.getCustomerId(),
                e.getDunningCaseId(),
                e.getAttemptNumber(),
                e.getAttemptSource(),
                e.getSimulationOutcome(),
                e.getStatus(),
                e.isRetryable(),
                e.getFailureCode(),
                e.getFailureReason(),
                Money.of(e.getAmount(), e.getCurrency()),
                e.getCurrency(),
                e.getIdempotencyKey(),
                e.getAttemptedAt()
        );
    }

    static PaymentDetail toPaymentDetail(PaymentTransactionEntity tx, List<PaymentAttemptEntity> attempts) {
        return new PaymentDetail(
                toTransactionSummary(tx),
                attempts.stream().map(PaymentMapper::toAttemptRecord).toList()
        );
    }

    static DunningPolicySummary toPolicySummary(DunningPolicyEntity e) {
        return new DunningPolicySummary(
                e.getId(),
                e.getPolicyCode(),
                e.getName(),
                e.getRetryOffsetsHours(),
                e.getSuspensionOffsetHours(),
                e.isActive(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }

    static DunningCaseSummary toCaseSummary(DunningCaseEntity e) {
        return new DunningCaseSummary(
                e.getId(),
                e.getInvoiceId(),
                e.getCustomerId(),
                e.getSubscriptionId(),
                e.getDunningPolicyId(),
                e.getStatus(),
                e.getInitialFailureAt(),
                e.getRetriesCompleted(),
                e.getNextRetryAt(),
                e.getSuspensionAt(),
                e.getSuspendedAt(),
                e.getRecoveredAt(),
                e.getLastFailureCode(),
                e.getLastFailureReason(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }

    static DunningEventRecord toEventRecord(DunningEventEntity e) {
        return new DunningEventRecord(
                e.getId(),
                e.getDunningCaseId(),
                e.getInvoiceId(),
                e.getCustomerId(),
                e.getSubscriptionId(),
                e.getEventType(),
                e.getFromStatus(),
                e.getToStatus(),
                e.getAttemptNumber(),
                e.getScheduledFor(),
                e.getDetails(),
                e.getOccurredAt()
        );
    }

    static DunningCaseDetail toCaseDetail(
            DunningCaseEntity dunningCase,
            List<DunningEventEntity> events,
            List<PaymentAttemptEntity> attempts
    ) {
        return new DunningCaseDetail(
                toCaseSummary(dunningCase),
                events.stream().map(PaymentMapper::toEventRecord).toList(),
                attempts.stream().map(PaymentMapper::toAttemptRecord).toList()
        );
    }
}
