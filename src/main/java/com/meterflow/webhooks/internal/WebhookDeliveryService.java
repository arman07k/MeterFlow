package com.meterflow.webhooks.internal;

import com.meterflow.identity.api.AuthenticatedActor;
import com.meterflow.identity.api.CustomerAccessGuard;
import com.meterflow.identity.api.PlatformRole;
import com.meterflow.webhooks.api.WebhookDeliveryDetail;
import com.meterflow.webhooks.api.WebhookDeliveryFacade;
import com.meterflow.webhooks.api.WebhookDeliveryStatus;
import com.meterflow.webhooks.api.WebhookDeliverySummary;
import com.meterflow.webhooks.api.WebhookDispatchSummary;
import com.meterflow.webhooks.api.WebhookResourceNotFoundException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class WebhookDeliveryService implements WebhookDeliveryFacade {

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookEndpointRepository endpointRepository;
    private final WebhookDeliveryAttemptRepository attemptRepository;
    private final WebhookDeliveryExecutor deliveryExecutor;
    private final CustomerAccessGuard accessGuard;
    private final WebhookProperties properties;

    WebhookDeliveryService(
            WebhookDeliveryRepository deliveryRepository,
            WebhookEndpointRepository endpointRepository,
            WebhookDeliveryAttemptRepository attemptRepository,
            WebhookDeliveryExecutor deliveryExecutor,
            CustomerAccessGuard accessGuard,
            WebhookProperties properties
    ) {
        this.deliveryRepository = deliveryRepository;
        this.endpointRepository = endpointRepository;
        this.attemptRepository = attemptRepository;
        this.deliveryExecutor = deliveryExecutor;
        this.accessGuard = accessGuard;
        this.properties = properties;
    }

    @Override
    @Transactional(readOnly = true)
    public WebhookDeliveryDetail getDeliveryDetail(UUID deliveryId) {
        WebhookDeliveryEntity delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new WebhookResourceNotFoundException("Webhook delivery not found"));
        accessGuard.requireCustomerAccess(delivery.getCustomerId());
        List<WebhookDeliveryAttemptEntity> attempts = attemptRepository
                .findByWebhookDeliveryIdOrderByAttemptedAtAsc(delivery.getId());
        return WebhookMapper.toDeliveryDetail(delivery, attempts);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<WebhookDeliverySummary> listCustomerDeliveries(
            UUID customerId,
            WebhookDeliveryStatus status,
            Pageable pageable
    ) {
        accessGuard.requireCustomerAccess(customerId);
        Page<WebhookDeliveryEntity> page = (status == null)
                ? deliveryRepository.findByCustomerIdOrderByCreatedAtDesc(customerId, pageable)
                : deliveryRepository.findByCustomerIdAndStatusOrderByCreatedAtDesc(customerId, status, pageable);
        return page.map(WebhookMapper::toDeliverySummary);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<WebhookDeliverySummary> listEndpointDeliveries(UUID endpointId, Pageable pageable) {
        WebhookEndpointEntity endpoint = endpointRepository.findById(endpointId)
                .orElseThrow(() -> new WebhookResourceNotFoundException("Webhook endpoint not found"));
        accessGuard.requireCustomerAccess(endpoint.getCustomerId());
        return deliveryRepository.findByWebhookEndpointIdOrderByCreatedAtDesc(endpointId, pageable)
                .map(WebhookMapper::toDeliverySummary);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<WebhookDeliverySummary> listAllDeliveries(WebhookDeliveryStatus status, Pageable pageable) {
        accessGuard.requireAnyRole(PlatformRole.ADMIN, PlatformRole.OPERATOR);
        Page<WebhookDeliveryEntity> page = (status == null)
                ? deliveryRepository.findAllByOrderByCreatedAtDesc(pageable)
                : deliveryRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
        return page.map(WebhookMapper::toDeliverySummary);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<WebhookDeliveryStatus, Long> countDeliveriesByStatus() {
        accessGuard.requireAnyRole(PlatformRole.ADMIN, PlatformRole.OPERATOR);
        Map<WebhookDeliveryStatus, Long> counts = new HashMap<>();
        for (WebhookDeliveryStatus status : WebhookDeliveryStatus.values()) {
            counts.put(status, deliveryRepository.countByStatus(status));
        }
        return Map.copyOf(counts);
    }

    @Override
    public WebhookDispatchSummary dispatchDueDeliveries() {
        int maxBatch = Math.max(1, properties.getBatchSize());
        int claimed = 0;
        int delivered = 0;
        int retrying = 0;
        int exhausted = 0;
        Set<UUID> visitedIds = new LinkedHashSet<>();

        for (int i = 0; i < maxBatch; i++) {
            Optional<WebhookDeliveryExecutor.ClaimedOutcome> outcome = deliveryExecutor.claimAndDeliverNextDue(visitedIds);
            if (outcome.isEmpty()) {
                break;
            }
            claimed++;
            visitedIds.add(outcome.get().deliveryId());
            switch (outcome.get().resultingStatus()) {
                case DELIVERED -> delivered++;
                case RETRYING -> retrying++;
                case EXHAUSTED -> exhausted++;
                case PENDING -> {
                }
            }
        }
        return new WebhookDispatchSummary(claimed, delivered, retrying, exhausted);
    }

    @Override
    public WebhookDeliveryDetail replayDelivery(UUID deliveryId) {
        WebhookDeliveryEntity existing = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new WebhookResourceNotFoundException("Webhook delivery not found"));
        AuthenticatedActor actor = accessGuard.requireAuthenticatedActor();
        accessGuard.requireCustomerAccess(existing.getCustomerId());
        return deliveryExecutor.executeManualReplay(deliveryId, actor.userId(), actor.email());
    }
}
