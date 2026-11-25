package com.meterflow.notifications.internal;

import com.meterflow.billing.api.CreditNoteIssuedEvent;
import com.meterflow.billing.api.InvoiceFinalizedEvent;
import com.meterflow.metering.api.UsageThresholdReachedEvent;
import com.meterflow.notifications.api.NotificationType;
import com.meterflow.payments.api.PaymentFailedEvent;
import com.meterflow.payments.api.PaymentSucceededEvent;
import com.meterflow.payments.api.SubscriptionReactivatedAfterPaymentEvent;
import com.meterflow.payments.api.SubscriptionSuspendedForNonPaymentEvent;
import com.meterflow.webhooks.api.WebhookReplayAuditEvent;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Listens to Spring Modulith domain events to persist idempotent customer notifications
 * and append-only audit log entries without external paid providers (FR9).
 */
@Component
class NotificationDomainEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationDomainEventListener.class);

    private final NotificationRepository notificationRepository;
    private final AuditLogRepository auditLogRepository;
    private final Clock clock;

    NotificationDomainEventListener(
            NotificationRepository notificationRepository,
            AuditLogRepository auditLogRepository,
            Clock clock
    ) {
        this.notificationRepository = notificationRepository;
        this.auditLogRepository = auditLogRepository;
        this.clock = clock;
    }

    @ApplicationModuleListener
    void onInvoiceFinalized(InvoiceFinalizedEvent event) {
        String title = "Invoice " + event.invoiceNumber() + " finalized";
        String message = "Invoice " + event.invoiceNumber() + " for " + event.totalAmount().formatted()
                + " has been finalized (payable due: " + event.payableAmountDue().formatted() + ").";
        persistNotification(
                event.customerId(),
                NotificationType.INVOICE_FINALIZED,
                title,
                message,
                "INVOICE",
                event.invoiceId(),
                deterministicSourceId("INVOICE_FINALIZED", event.invoiceId().toString()),
                event.finalizedAt()
        );
    }

    @ApplicationModuleListener
    void onPaymentSucceeded(PaymentSucceededEvent event) {
        String title = "Payment received (" + event.paymentReference() + ")";
        String message = "Payment of " + event.amount().formatted() + " was captured successfully.";
        persistNotification(
                event.customerId(),
                NotificationType.PAYMENT_SUCCEEDED,
                title,
                message,
                "PAYMENT",
                event.paymentTransactionId(),
                deterministicSourceId("PAYMENT_SUCCEEDED", event.paymentTransactionId().toString()),
                event.capturedAt()
        );
    }

    @ApplicationModuleListener
    void onPaymentFailed(PaymentFailedEvent event) {
        String title = "Payment failed (attempt #" + event.attemptNumber() + ")";
        String message = "Payment of " + event.amount().formatted() + " failed ("
                + event.failureCode() + "): " + event.failureReason();
        persistNotification(
                event.customerId(),
                NotificationType.PAYMENT_FAILED,
                title,
                message,
                "PAYMENT",
                event.paymentTransactionId(),
                deterministicSourceId("PAYMENT_FAILED", event.paymentTransactionId() + ":" + event.attemptNumber()),
                event.failedAt()
        );
    }

    @ApplicationModuleListener
    void onSubscriptionSuspended(SubscriptionSuspendedForNonPaymentEvent event) {
        String title = "Subscription suspended for non-payment";
        String message = "Your subscription was suspended after " + event.retriesCompleted()
                + " dunning retry attempts: " + event.reason();
        persistNotification(
                event.customerId(),
                NotificationType.SUBSCRIPTION_SUSPENDED,
                title,
                message,
                "SUBSCRIPTION",
                event.subscriptionId(),
                deterministicSourceId("SUBSCRIPTION_SUSPENDED", event.dunningCaseId() + ":" + event.suspendedAt()),
                event.suspendedAt()
        );
    }

    @ApplicationModuleListener
    void onSubscriptionReactivated(SubscriptionReactivatedAfterPaymentEvent event) {
        String title = "Subscription reactivated";
        String message = "Your subscription returned to ACTIVE status from " + event.previousStatus()
                + " following invoice settlement.";
        persistNotification(
                event.customerId(),
                NotificationType.SUBSCRIPTION_REACTIVATED,
                title,
                message,
                "SUBSCRIPTION",
                event.subscriptionId(),
                deterministicSourceId("SUBSCRIPTION_REACTIVATED", event.paymentTransactionId().toString()),
                event.reactivatedAt()
        );
    }

    @ApplicationModuleListener
    void onUsageThresholdReached(UsageThresholdReachedEvent event) {
        String title = "Usage threshold " + event.thresholdPercent() + "% reached";
        String message = "Usage for scope '" + event.scopeKey() + "' reached "
                + event.currentValue().stripTrailingZeros().toPlainString()
                + " (" + event.utilizationPercent().stripTrailingZeros().toPlainString()
                + "% of limit " + event.limitValue().stripTrailingZeros().toPlainString() + ").";
        persistNotification(
                event.customerId(),
                NotificationType.USAGE_THRESHOLD_REACHED,
                title,
                message,
                "METRIC",
                event.metricId(),
                deterministicSourceId("USAGE_THRESHOLD", event.evaluationId().toString()),
                event.triggeredAt()
        );
    }

    @ApplicationModuleListener
    void onCreditNoteIssued(CreditNoteIssuedEvent event) {
        String title = "Credit note " + event.creditNoteNumber() + " issued";
        String message = "Credit note " + event.creditNoteNumber() + " for " + event.totalCredit().formatted()
                + " has been issued (applied to invoice: " + event.appliedToInvoiceDue().formatted()
                + ", credit wallet: " + event.refundedToCreditWallet().formatted() + ").";
        persistNotification(
                event.customerId(),
                NotificationType.CREDIT_NOTE_ISSUED,
                title,
                message,
                "CREDIT_NOTE",
                event.creditNoteId(),
                deterministicSourceId("CREDIT_NOTE", event.creditNoteId().toString()),
                event.issuedAt()
        );
    }

    @ApplicationModuleListener
    void onWebhookManualReplay(WebhookReplayAuditEvent event) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("endpointId", event.endpointId());
        details.put("eventType", event.eventType());
        details.put("httpStatus", event.httpStatus());
        details.put("resultingStatus", event.resultingStatus() != null ? event.resultingStatus().name() : null);
        Instant occurredAt = event.replayedAt() != null
                ? event.replayedAt()
                : clock.instant().truncatedTo(ChronoUnit.MICROS);
        auditLogRepository.saveAndFlush(new AuditLogEntity(
                event.actorUserId(),
                event.actorEmail(),
                event.customerId(),
                "WEBHOOK_DELIVERY_REPLAY",
                "WEBHOOK_DELIVERY",
                event.deliveryId(),
                details,
                occurredAt
        ));
    }

    private void persistNotification(
            UUID customerId,
            NotificationType type,
            String title,
            String message,
            String resourceType,
            UUID resourceId,
            UUID sourceEventId,
            Instant occurredAt
    ) {
        if (notificationRepository.existsByCustomerIdAndNotificationTypeAndSourceEventId(
                customerId,
                type,
                sourceEventId
        )) {
            return;
        }
        Instant timestamp = occurredAt != null
                ? occurredAt.truncatedTo(ChronoUnit.MICROS)
                : clock.instant().truncatedTo(ChronoUnit.MICROS);
        try {
            notificationRepository.saveAndFlush(new NotificationEntity(
                    customerId,
                    type,
                    title,
                    message,
                    resourceType,
                    resourceId,
                    sourceEventId,
                    timestamp
            ));
        } catch (DataIntegrityViolationException ex) {
            log.debug("Duplicate notification ignored for customer={} type={} sourceEvent={}", customerId, type, sourceEventId);
        }
    }

    private static UUID deterministicSourceId(String prefix, String discriminator) {
        return UUID.nameUUIDFromBytes(("meterflow:notification:" + prefix + ":" + discriminator)
                .getBytes(StandardCharsets.UTF_8));
    }
}
