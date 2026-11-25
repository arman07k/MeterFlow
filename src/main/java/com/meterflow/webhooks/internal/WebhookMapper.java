package com.meterflow.webhooks.internal;

import com.meterflow.webhooks.api.WebhookDeliveryAttemptRecord;
import com.meterflow.webhooks.api.WebhookDeliveryDetail;
import com.meterflow.webhooks.api.WebhookDeliverySummary;
import com.meterflow.webhooks.api.WebhookEndpointSummary;
import java.time.Instant;
import java.util.List;

final class WebhookMapper {

    private WebhookMapper() {
    }

    static WebhookEndpointSummary toEndpointSummary(WebhookEndpointEntity entity, Instant now) {
        return new WebhookEndpointSummary(
                entity.getId(),
                entity.getCustomerId(),
                entity.getName(),
                entity.getTargetUrl(),
                entity.getSubscribedEvents(),
                entity.getSecretPrefix(),
                entity.isActive(),
                entity.isDualKeyGraceActive(now),
                entity.getPreviousSecretExpiresAt(),
                entity.getDeletedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    static WebhookDeliverySummary toDeliverySummary(WebhookDeliveryEntity entity) {
        return new WebhookDeliverySummary(
                entity.getId(),
                entity.getWebhookEndpointId(),
                entity.getCustomerId(),
                entity.getSourceEventId(),
                entity.getEventType(),
                entity.getEventVersion(),
                entity.getIdempotencyKey(),
                entity.getStatus(),
                entity.getAttemptCount(),
                entity.getMaxAttempts(),
                entity.getNextAttemptAt(),
                entity.getLastAttemptedAt(),
                entity.getDeliveredAt(),
                entity.getLastHttpStatus(),
                entity.getLastErrorReason(),
                entity.getCreatedAt()
        );
    }

    static WebhookDeliveryAttemptRecord toAttemptRecord(WebhookDeliveryAttemptEntity entity) {
        return new WebhookDeliveryAttemptRecord(
                entity.getId(),
                entity.getWebhookDeliveryId(),
                entity.getWebhookEndpointId(),
                entity.getCustomerId(),
                entity.getAttemptNumber(),
                entity.isManualReplay(),
                entity.getReplayedByUserId(),
                entity.getSignatureTimestamp(),
                entity.getHttpStatus(),
                entity.getDurationMs(),
                entity.getResponseExcerpt(),
                entity.getErrorReason(),
                entity.getAttemptedAt()
        );
    }

    static WebhookDeliveryDetail toDeliveryDetail(
            WebhookDeliveryEntity delivery,
            List<WebhookDeliveryAttemptEntity> attempts
    ) {
        return new WebhookDeliveryDetail(
                toDeliverySummary(delivery),
                delivery.getPayloadJson(),
                attempts.stream().map(WebhookMapper::toAttemptRecord).toList()
        );
    }
}
