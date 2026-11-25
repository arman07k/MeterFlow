package com.meterflow.webhooks.api;

import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Public facade for inspecting webhook deliveries, running bounded due dispatch, and auditable replay (FR9).
 */
public interface WebhookDeliveryFacade {

    WebhookDeliveryDetail getDeliveryDetail(UUID deliveryId);

    Page<WebhookDeliverySummary> listCustomerDeliveries(
            UUID customerId,
            WebhookDeliveryStatus status,
            Pageable pageable
    );

    Page<WebhookDeliverySummary> listEndpointDeliveries(UUID endpointId, Pageable pageable);

    Page<WebhookDeliverySummary> listAllDeliveries(WebhookDeliveryStatus status, Pageable pageable);

    Map<WebhookDeliveryStatus, Long> countDeliveriesByStatus();

    WebhookDispatchSummary dispatchDueDeliveries();

    WebhookDeliveryDetail replayDelivery(UUID deliveryId);
}
