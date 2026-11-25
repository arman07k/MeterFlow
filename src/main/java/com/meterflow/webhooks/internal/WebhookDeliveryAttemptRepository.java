package com.meterflow.webhooks.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface WebhookDeliveryAttemptRepository extends JpaRepository<WebhookDeliveryAttemptEntity, UUID> {

    List<WebhookDeliveryAttemptEntity> findByWebhookDeliveryIdOrderByAttemptedAtAsc(UUID webhookDeliveryId);
}
