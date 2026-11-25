package com.meterflow.webhooks.internal;

import com.meterflow.billing.api.CreditNoteIssuedEvent;
import com.meterflow.billing.api.InvoiceFinalizedEvent;
import com.meterflow.metering.api.UsageThresholdReachedEvent;
import com.meterflow.payments.api.PaymentFailedEvent;
import com.meterflow.payments.api.PaymentSucceededEvent;
import com.meterflow.payments.api.SubscriptionReactivatedAfterPaymentEvent;
import com.meterflow.payments.api.SubscriptionSuspendedForNonPaymentEvent;
import com.meterflow.webhooks.api.WebhookEventTypes;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Listens to transactional Spring Modulith outbox domain events and enqueues idempotent outbound
 * webhook deliveries for every matching active customer endpoint (FR9, ADR-009).
 */
@Component
class WebhookDomainEventListener {

    private static final Logger log = LoggerFactory.getLogger(WebhookDomainEventListener.class);
    private static final String EVENT_VERSION = "1.0";

    private final WebhookEndpointRepository endpointRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookMetrics metrics;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    WebhookDomainEventListener(
            WebhookEndpointRepository endpointRepository,
            WebhookDeliveryRepository deliveryRepository,
            WebhookMetrics metrics,
            JsonMapper jsonMapper,
            Clock clock
    ) {
        this.endpointRepository = endpointRepository;
        this.deliveryRepository = deliveryRepository;
        this.metrics = metrics;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @ApplicationModuleListener
    void onInvoiceFinalized(InvoiceFinalizedEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("invoiceId", event.invoiceId());
        data.put("invoiceNumber", event.invoiceNumber());
        data.put("customerId", event.customerId());
        data.put("subscriptionId", event.subscriptionId());
        data.put("currency", event.currency().name());
        data.put("totalAmount", event.totalAmount().amountAsPlainString());
        data.put("creditsAppliedAmount", event.creditsAppliedAmount().amountAsPlainString());
        data.put("payableAmountDue", event.payableAmountDue().amountAsPlainString());
        data.put("periodStart", event.periodStart());
        data.put("periodEnd", event.periodEnd());
        data.put("finalizedAt", event.finalizedAt());
        data.put("dueAt", event.dueAt());

        UUID sourceEventId = deterministicEventId(WebhookEventTypes.INVOICE_FINALISED, event.invoiceId().toString());
        enqueueForCustomer(event.customerId(), sourceEventId, WebhookEventTypes.INVOICE_FINALISED, event.finalizedAt(), data);
    }

    @ApplicationModuleListener
    void onPaymentFailed(PaymentFailedEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentTransactionId", event.paymentTransactionId());
        data.put("paymentReference", event.paymentReference());
        data.put("invoiceId", event.invoiceId());
        data.put("customerId", event.customerId());
        data.put("subscriptionId", event.subscriptionId());
        data.put("dunningCaseId", event.dunningCaseId());
        data.put("currency", event.currency().name());
        data.put("amount", event.amount().amountAsPlainString());
        data.put("attemptNumber", event.attemptNumber());
        data.put("retryable", event.retryable());
        data.put("failureCode", event.failureCode());
        data.put("failureReason", event.failureReason());
        data.put("dunningStatus", event.dunningStatus() != null ? event.dunningStatus().name() : null);
        data.put("nextRetryAt", event.nextRetryAt());
        data.put("suspensionAt", event.suspensionAt());
        data.put("failedAt", event.failedAt());

        UUID sourceEventId = deterministicEventId(
                WebhookEventTypes.PAYMENT_FAILED,
                event.paymentTransactionId() + ":" + event.attemptNumber()
        );
        enqueueForCustomer(event.customerId(), sourceEventId, WebhookEventTypes.PAYMENT_FAILED, event.failedAt(), data);
    }

    @ApplicationModuleListener
    void onUsageThresholdReached(UsageThresholdReachedEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("evaluationId", event.evaluationId());
        data.put("customerId", event.customerId());
        data.put("subscriptionId", event.subscriptionId());
        data.put("metricId", event.metricId());
        data.put("scopeKey", event.scopeKey());
        data.put("windowStart", event.windowStart());
        data.put("windowEnd", event.windowEnd());
        data.put("thresholdPercent", event.thresholdPercent());
        data.put("limitValue", event.limitValue());
        data.put("currentValue", event.currentValue());
        data.put("utilizationPercent", event.utilizationPercent());
        data.put("triggeredAt", event.triggeredAt());

        UUID sourceEventId = deterministicEventId(
                WebhookEventTypes.USAGE_THRESHOLD_REACHED,
                event.evaluationId().toString()
        );
        enqueueForCustomer(
                event.customerId(),
                sourceEventId,
                WebhookEventTypes.USAGE_THRESHOLD_REACHED,
                event.triggeredAt(),
                data
        );
    }

    @ApplicationModuleListener
    void onPaymentSucceeded(PaymentSucceededEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentTransactionId", event.paymentTransactionId());
        data.put("paymentReference", event.paymentReference());
        data.put("invoiceId", event.invoiceId());
        data.put("customerId", event.customerId());
        data.put("subscriptionId", event.subscriptionId());
        data.put("currency", event.currency().name());
        data.put("amount", event.amount().amountAsPlainString());
        data.put("attemptNumber", event.attemptNumber());
        data.put("recoveredFromDunning", event.recoveredFromDunning());
        data.put("capturedAt", event.capturedAt());

        UUID sourceEventId = deterministicEventId(
                WebhookEventTypes.PAYMENT_SUCCEEDED,
                event.paymentTransactionId().toString()
        );
        enqueueForCustomer(event.customerId(), sourceEventId, WebhookEventTypes.PAYMENT_SUCCEEDED, event.capturedAt(), data);
    }

    @ApplicationModuleListener
    void onCreditNoteIssued(CreditNoteIssuedEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("creditNoteId", event.creditNoteId());
        data.put("creditNoteNumber", event.creditNoteNumber());
        data.put("originalInvoiceId", event.originalInvoiceId());
        data.put("customerId", event.customerId());
        data.put("currency", event.currency().name());
        data.put("totalCredit", event.totalCredit().amountAsPlainString());
        data.put("appliedToInvoiceDue", event.appliedToInvoiceDue().amountAsPlainString());
        data.put("refundedToCreditWallet", event.refundedToCreditWallet().amountAsPlainString());
        data.put("issuedAt", event.issuedAt());

        UUID sourceEventId = deterministicEventId(
                WebhookEventTypes.CREDIT_NOTE_ISSUED,
                event.creditNoteId().toString()
        );
        enqueueForCustomer(event.customerId(), sourceEventId, WebhookEventTypes.CREDIT_NOTE_ISSUED, event.issuedAt(), data);
    }

    @ApplicationModuleListener
    void onSubscriptionSuspended(SubscriptionSuspendedForNonPaymentEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dunningCaseId", event.dunningCaseId());
        data.put("invoiceId", event.invoiceId());
        data.put("customerId", event.customerId());
        data.put("subscriptionId", event.subscriptionId());
        data.put("retriesCompleted", event.retriesCompleted());
        data.put("reason", event.reason());
        data.put("suspendedAt", event.suspendedAt());

        UUID sourceEventId = deterministicEventId(
                WebhookEventTypes.SUBSCRIPTION_SUSPENDED,
                event.dunningCaseId() + ":" + event.suspendedAt()
        );
        enqueueForCustomer(
                event.customerId(),
                sourceEventId,
                WebhookEventTypes.SUBSCRIPTION_SUSPENDED,
                event.suspendedAt(),
                data
        );
    }

    @ApplicationModuleListener
    void onSubscriptionReactivated(SubscriptionReactivatedAfterPaymentEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentTransactionId", event.paymentTransactionId());
        data.put("dunningCaseId", event.dunningCaseId());
        data.put("invoiceId", event.invoiceId());
        data.put("customerId", event.customerId());
        data.put("subscriptionId", event.subscriptionId());
        data.put("previousStatus", event.previousStatus());
        data.put("reactivatedAt", event.reactivatedAt());

        UUID sourceEventId = deterministicEventId(
                WebhookEventTypes.SUBSCRIPTION_REACTIVATED,
                event.paymentTransactionId() + ":" + event.subscriptionId()
        );
        enqueueForCustomer(
                event.customerId(),
                sourceEventId,
                WebhookEventTypes.SUBSCRIPTION_REACTIVATED,
                event.reactivatedAt(),
                data
        );
    }

    private void enqueueForCustomer(
            UUID customerId,
            UUID sourceEventId,
            String eventType,
            Instant occurredAt,
            Map<String, Object> data
    ) {
        List<WebhookEndpointEntity> endpoints = endpointRepository
                .findByCustomerIdAndActiveTrueAndDeletedAtIsNull(customerId);
        if (endpoints.isEmpty()) {
            return;
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String payloadJson = buildEnvelopeJson(sourceEventId, eventType, customerId, occurredAt != null ? occurredAt : now, data);

        for (WebhookEndpointEntity endpoint : endpoints) {
            if (!endpoint.subscribesTo(eventType)) {
                continue;
            }
            if (deliveryRepository.existsByWebhookEndpointIdAndSourceEventId(endpoint.getId(), sourceEventId)) {
                continue;
            }
            String idempotencyKey = eventType + ":" + sourceEventId + ":" + endpoint.getId();
            WebhookDeliveryEntity delivery = new WebhookDeliveryEntity(
                    endpoint.getId(),
                    customerId,
                    sourceEventId,
                    eventType,
                    EVENT_VERSION,
                    idempotencyKey,
                    payloadJson,
                    WebhookBackoffPolicy.MAX_ATTEMPTS,
                    now
            );
            try {
                deliveryRepository.saveAndFlush(delivery);
                metrics.recordEnqueued(eventType);
            } catch (DataIntegrityViolationException ex) {
                log.debug(
                        "Duplicate webhook delivery ignored for endpoint={} sourceEventId={}",
                        endpoint.getId(),
                        sourceEventId
                );
            }
        }
    }

    private String buildEnvelopeJson(
            UUID sourceEventId,
            String eventType,
            UUID customerId,
            Instant occurredAt,
            Map<String, Object> data
    ) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", sourceEventId);
        envelope.put("eventType", eventType);
        envelope.put("eventVersion", EVENT_VERSION);
        envelope.put("customerId", customerId);
        envelope.put("occurredAt", occurredAt);
        envelope.put("data", data);
        return jsonMapper.writeValueAsString(envelope);
    }

    private static UUID deterministicEventId(String eventType, String uniqueDiscriminator) {
        String seed = "meterflow:webhook:" + eventType + ":" + uniqueDiscriminator;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
