package com.meterflow.webhooks.internal;

import com.meterflow.webhooks.api.WebhookDeliveryDetail;
import com.meterflow.webhooks.api.WebhookDeliveryStatus;
import com.meterflow.webhooks.api.WebhookReplayAuditEvent;
import com.meterflow.webhooks.api.WebhookReplayNotAllowedException;
import com.meterflow.webhooks.api.WebhookResourceNotFoundException;
import com.meterflow.webhooks.api.WebhookSignatureVerifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolated per-delivery transactional executor using PostgreSQL FOR UPDATE SKIP LOCKED (ADR-009).
 */
@Component
class WebhookDeliveryExecutor {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryExecutor.class);

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookEndpointRepository endpointRepository;
    private final WebhookDeliveryAttemptRepository attemptRepository;
    private final WebhookSecretCipher secretCipher;
    private final SsrfUrlValidator urlValidator;
    private final HttpClient httpClient;
    private final WebhookProperties properties;
    private final WebhookMetrics metrics;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    WebhookDeliveryExecutor(
            WebhookDeliveryRepository deliveryRepository,
            WebhookEndpointRepository endpointRepository,
            WebhookDeliveryAttemptRepository attemptRepository,
            WebhookSecretCipher secretCipher,
            SsrfUrlValidator urlValidator,
            HttpClient httpClient,
            WebhookProperties properties,
            WebhookMetrics metrics,
            ApplicationEventPublisher eventPublisher,
            Clock clock
    ) {
        this.deliveryRepository = deliveryRepository;
        this.endpointRepository = endpointRepository;
        this.attemptRepository = attemptRepository;
        this.secretCipher = secretCipher;
        this.urlValidator = urlValidator;
        this.httpClient = httpClient;
        this.properties = properties;
        this.metrics = metrics;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    record ClaimedOutcome(UUID deliveryId, WebhookDeliveryStatus resultingStatus) {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Optional<ClaimedOutcome> claimAndDeliverNextDue(Collection<UUID> excludedIds) {
        Instant now = nowUtc();
        Optional<WebhookDeliveryEntity> claimed = (excludedIds == null || excludedIds.isEmpty())
                ? deliveryRepository.claimNextDue(now)
                : deliveryRepository.claimNextDueExcluding(now, excludedIds);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        WebhookDeliveryEntity delivery = claimed.get();
        WebhookEndpointEntity endpoint = endpointRepository.findById(delivery.getWebhookEndpointId()).orElse(null);

        if (endpoint == null || endpoint.isDeleted() || !endpoint.isActive()) {
            String reason = (endpoint == null || endpoint.isDeleted())
                    ? "Webhook endpoint deleted before delivery"
                    : "Webhook endpoint disabled before delivery";
            delivery.markExhausted(null, reason, now);
            deliveryRepository.saveAndFlush(delivery);
            metrics.recordDeliveryOutcome(delivery.getEventType(), "EXHAUSTED");
            return Optional.of(new ClaimedOutcome(delivery.getId(), delivery.getStatus()));
        }

        HttpAttemptResult result = sendSignedPost(delivery, endpoint, now);
        int attemptNumber = delivery.nextAttemptNumber();
        attemptRepository.saveAndFlush(new WebhookDeliveryAttemptEntity(
                delivery.getId(),
                endpoint.getId(),
                delivery.getCustomerId(),
                attemptNumber,
                false,
                null,
                result.signatureEpochSeconds(),
                result.httpStatus(),
                result.durationMs(),
                result.responseExcerpt(),
                result.errorReason(),
                now
        ));

        if (WebhookBackoffPolicy.isSuccessStatus(result.httpStatus())) {
            delivery.markDelivered(result.httpStatus(), now);
            metrics.recordDeliveryOutcome(delivery.getEventType(), "DELIVERED");
        } else if (WebhookBackoffPolicy.isRetryableFailure(result.httpStatus())) {
            Optional<Instant> nextRetry = WebhookBackoffPolicy.nextRetryAt(
                    attemptNumber,
                    delivery.getMaxAttempts(),
                    now
            );
            if (nextRetry.isPresent()) {
                delivery.markScheduledForRetry(result.httpStatus(), result.errorReason(), nextRetry.get(), now);
                metrics.recordDeliveryOutcome(delivery.getEventType(), "RETRYING");
            } else {
                delivery.markExhausted(result.httpStatus(), result.errorReason(), now);
                metrics.recordDeliveryOutcome(delivery.getEventType(), "EXHAUSTED");
            }
        } else {
            delivery.markExhausted(result.httpStatus(), result.errorReason(), now);
            metrics.recordDeliveryOutcome(delivery.getEventType(), "EXHAUSTED");
        }

        deliveryRepository.saveAndFlush(delivery);
        return Optional.of(new ClaimedOutcome(delivery.getId(), delivery.getStatus()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    WebhookDeliveryDetail executeManualReplay(UUID deliveryId, UUID actorUserId, String actorEmail) {
        WebhookDeliveryEntity delivery = deliveryRepository.findByIdForUpdate(deliveryId)
                .orElseThrow(() -> new WebhookResourceNotFoundException("Webhook delivery not found"));
        WebhookEndpointEntity endpoint = endpointRepository.findById(delivery.getWebhookEndpointId())
                .orElseThrow(() -> new WebhookReplayNotAllowedException("Associated webhook endpoint no longer exists"));
        if (endpoint.isDeleted() || !endpoint.isActive()) {
            throw new WebhookReplayNotAllowedException(
                    "Cannot replay delivery for a disabled or deleted webhook endpoint"
            );
        }

        Instant now = nowUtc();
        HttpAttemptResult result = sendSignedPost(delivery, endpoint, now);
        int attemptNumber = delivery.nextAttemptNumber();
        attemptRepository.saveAndFlush(new WebhookDeliveryAttemptEntity(
                delivery.getId(),
                endpoint.getId(),
                delivery.getCustomerId(),
                attemptNumber,
                true,
                actorUserId,
                result.signatureEpochSeconds(),
                result.httpStatus(),
                result.durationMs(),
                result.responseExcerpt(),
                result.errorReason(),
                now
        ));

        if (WebhookBackoffPolicy.isSuccessStatus(result.httpStatus())) {
            delivery.markDelivered(result.httpStatus(), now);
            metrics.recordManualReplay("DELIVERED");
        } else {
            delivery.recordManualReplayFailure(result.httpStatus(), result.errorReason(), now);
            metrics.recordManualReplay("FAILED");
        }
        WebhookDeliveryEntity saved = deliveryRepository.saveAndFlush(delivery);

        eventPublisher.publishEvent(new WebhookReplayAuditEvent(
                saved.getId(),
                endpoint.getId(),
                saved.getCustomerId(),
                saved.getEventType(),
                actorUserId,
                actorEmail,
                result.httpStatus(),
                saved.getStatus(),
                now
        ));

        List<WebhookDeliveryAttemptEntity> attempts = attemptRepository
                .findByWebhookDeliveryIdOrderByAttemptedAtAsc(saved.getId());
        return WebhookMapper.toDeliveryDetail(saved, attempts);
    }

    private HttpAttemptResult sendSignedPost(
            WebhookDeliveryEntity delivery,
            WebhookEndpointEntity endpoint,
            Instant attemptTime
    ) {
        long signatureEpoch = attemptTime.getEpochSecond();
        long startNanos = System.nanoTime();
        try {
            URI targetUri = urlValidator.validateAndNormalize(endpoint.getTargetUrl());
            String signatureHeader = buildDualKeySignatureHeader(endpoint, signatureEpoch, delivery.getPayloadJson(), attemptTime);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(targetUri)
                    .timeout(Duration.ofMillis(Math.max(1000, properties.getTimeoutMs())))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("User-Agent", "MeterFlow-Webhooks/1.0")
                    .header(WebhookSignatureVerifier.SIGNATURE_HEADER, signatureHeader)
                    .header(WebhookSignatureVerifier.DELIVERY_ID_HEADER, delivery.getId().toString())
                    .header(WebhookSignatureVerifier.EVENT_TYPE_HEADER, delivery.getEventType())
                    .header(WebhookSignatureVerifier.EVENT_VERSION_HEADER, delivery.getEventVersion())
                    .header(WebhookSignatureVerifier.IDEMPOTENCY_KEY_HEADER, delivery.getIdempotencyKey())
                    .POST(HttpRequest.BodyPublishers.ofString(delivery.getPayloadJson(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int durationMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startNanos) / 1_000_000L);
            int statusCode = response.statusCode();
            String bodyExcerpt = sanitizeExcerpt(response.body());
            String errorReason = WebhookBackoffPolicy.isSuccessStatus(statusCode)
                    ? null
                    : "HTTP " + statusCode + (bodyExcerpt != null ? ": " + bodyExcerpt : "");
            return new HttpAttemptResult(signatureEpoch, statusCode, durationMs, bodyExcerpt, errorReason);
        } catch (Exception ex) {
            int durationMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startNanos) / 1_000_000L);
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String reason = ex.getClass().getSimpleName() + ": " + (ex.getMessage() != null ? ex.getMessage() : "Delivery transport failure");
            log.warn("Webhook delivery {} failed with transport error: {}", delivery.getId(), reason);
            return new HttpAttemptResult(signatureEpoch, null, durationMs, null, reason);
        }
    }

    private String buildDualKeySignatureHeader(
            WebhookEndpointEntity endpoint,
            long signatureEpoch,
            String payloadJson,
            Instant attemptTime
    ) {
        List<String> signatures = new ArrayList<>(2);
        String activeSecret = secretCipher.decrypt(endpoint.getActiveSigningSecretEnc());
        signatures.add(WebhookSignatureVerifier.computeSignature(activeSecret, signatureEpoch, payloadJson));
        if (endpoint.isDualKeyGraceActive(attemptTime)) {
            String previousSecret = secretCipher.decrypt(endpoint.getPreviousSigningSecretEnc());
            signatures.add(WebhookSignatureVerifier.computeSignature(previousSecret, signatureEpoch, payloadJson));
        }
        return WebhookSignatureVerifier.buildSignatureHeader(signatureEpoch, signatures);
    }

    private static String sanitizeExcerpt(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        String collapsed = rawBody.replaceAll("[\\r\\n\\t]+", " ").trim();
        return collapsed.length() <= 300 ? collapsed : collapsed.substring(0, 300);
    }

    private Instant nowUtc() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private record HttpAttemptResult(
            long signatureEpochSeconds,
            Integer httpStatus,
            int durationMs,
            String responseExcerpt,
            String errorReason
    ) {
    }
}
