package com.meterflow.webhooks.api;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Public facade for webhook endpoint registration, updates, secret rotation, and safe deletion (FR9).
 */
public interface WebhookEndpointFacade {

    WebhookSecretDisclosure registerEndpoint(RegisterWebhookEndpointCommand command);

    WebhookEndpointSummary updateEndpoint(UUID endpointId, UpdateWebhookEndpointCommand command);

    WebhookSecretDisclosure rotateSecret(UUID endpointId);

    void deleteEndpoint(UUID endpointId);

    WebhookEndpointSummary getEndpoint(UUID endpointId);

    List<WebhookEndpointSummary> listCustomerEndpoints(UUID customerId);

    Page<WebhookEndpointSummary> listAllEndpoints(Pageable pageable);
}
