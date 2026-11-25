package com.meterflow.payments.internal;

import com.meterflow.payments.api.PaymentFailedEvent;
import com.meterflow.payments.api.PaymentRetryScheduledEvent;
import com.meterflow.payments.api.PaymentSucceededEvent;
import com.meterflow.payments.api.SubscriptionReactivatedAfterPaymentEvent;
import com.meterflow.payments.api.SubscriptionSuspendedForNonPaymentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Durable Spring Modulith transactional outbox audit listener for Payments & Dunning events
 * (FR8, ADR-004, ADR-015). Ensures all five domain events persist atomically in PostgreSQL
 * {@code event_publication} ready for future FR9 outbound webhooks and notifications.
 */
@Component
class PaymentAuditEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(PaymentAuditEventListener.class);

    @ApplicationModuleListener
    void onPaymentSucceeded(PaymentSucceededEvent event) {
        LOGGER.info(
                "Recorded payment succeeded outbox event: paymentRef={} invoiceId={} amount={} recovered={}",
                event.paymentReference(), event.invoiceId(), event.amount(), event.recoveredFromDunning());
    }

    @ApplicationModuleListener
    void onPaymentFailed(PaymentFailedEvent event) {
        LOGGER.info(
                "Recorded payment failed outbox event: paymentRef={} invoiceId={} code={} retryable={} dunningStatus={}",
                event.paymentReference(), event.invoiceId(), event.failureCode(),
                event.retryable(), event.dunningStatus());
    }

    @ApplicationModuleListener
    void onPaymentRetryScheduled(PaymentRetryScheduledEvent event) {
        LOGGER.info(
                "Recorded dunning schedule outbox event: caseId={} status={} retriesCompleted={} nextRetryAt={} suspensionAt={}",
                event.dunningCaseId(), event.dunningStatus(), event.retriesCompleted(),
                event.nextRetryAt(), event.suspensionAt());
    }

    @ApplicationModuleListener
    void onSubscriptionSuspended(SubscriptionSuspendedForNonPaymentEvent event) {
        LOGGER.info(
                "Recorded subscription suspension outbox event: caseId={} subscriptionId={} retriesCompleted={}",
                event.dunningCaseId(), event.subscriptionId(), event.retriesCompleted());
    }

    @ApplicationModuleListener
    void onSubscriptionReactivated(SubscriptionReactivatedAfterPaymentEvent event) {
        LOGGER.info(
                "Recorded subscription reactivation outbox event: paymentId={} subscriptionId={} previousStatus={}",
                event.paymentTransactionId(), event.subscriptionId(), event.previousStatus());
    }
}
