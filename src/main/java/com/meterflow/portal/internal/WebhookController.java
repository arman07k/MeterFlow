package com.meterflow.portal.internal;

import com.meterflow.webhooks.api.RegisterWebhookEndpointCommand;
import com.meterflow.webhooks.api.UpdateWebhookEndpointCommand;
import com.meterflow.webhooks.api.WebhookDeliveryDetail;
import com.meterflow.webhooks.api.WebhookDeliveryFacade;
import com.meterflow.webhooks.api.WebhookDeliveryStatus;
import com.meterflow.webhooks.api.WebhookDeliverySummary;
import com.meterflow.webhooks.api.WebhookDispatchSummary;
import com.meterflow.webhooks.api.WebhookEndpointFacade;
import com.meterflow.webhooks.api.WebhookEndpointSummary;
import com.meterflow.webhooks.api.WebhookSecretDisclosure;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin REST adapter for outbound webhook endpoints, delivery logs, secret rotation, and auditable replay (FR9).
 */
@RestController
@RequestMapping("/api/v1")
class WebhookController {

    private static final int MAX_PAGE_SIZE = 100;

    private final WebhookEndpointFacade endpointFacade;
    private final WebhookDeliveryFacade deliveryFacade;

    WebhookController(WebhookEndpointFacade endpointFacade, WebhookDeliveryFacade deliveryFacade) {
        this.endpointFacade = endpointFacade;
        this.deliveryFacade = deliveryFacade;
    }

    @PostMapping("/customer/{customerId}/webhooks/endpoints")
    @ResponseStatus(HttpStatus.CREATED)
    WebhookSecretDisclosure registerCustomerEndpoint(
            @PathVariable UUID customerId,
            @Valid @RequestBody RegisterEndpointRequest request
    ) {
        return endpointFacade.registerEndpoint(new RegisterWebhookEndpointCommand(
                customerId,
                request.name(),
                request.targetUrl(),
                request.subscribedEvents()
        ));
    }

    @GetMapping("/customer/{customerId}/webhooks/endpoints")
    List<WebhookEndpointSummary> listCustomerEndpoints(@PathVariable UUID customerId) {
        return endpointFacade.listCustomerEndpoints(customerId);
    }

    @PatchMapping("/customer/{customerId}/webhooks/endpoints/{endpointId}")
    WebhookEndpointSummary updateCustomerEndpoint(
            @PathVariable UUID customerId,
            @PathVariable UUID endpointId,
            @RequestBody UpdateEndpointRequest request
    ) {
        WebhookEndpointSummary existing = endpointFacade.getEndpoint(endpointId);
        if (!existing.customerId().equals(customerId)) {
            throw new com.meterflow.identity.api.UnauthorizedCustomerAccessException("Endpoint does not belong to customer");
        }
        return endpointFacade.updateEndpoint(endpointId, new UpdateWebhookEndpointCommand(
                request.name(),
                request.targetUrl(),
                request.subscribedEvents(),
                request.active()
        ));
    }

    @PostMapping("/customer/{customerId}/webhooks/endpoints/{endpointId}/rotate-secret")
    WebhookSecretDisclosure rotateCustomerEndpointSecret(
            @PathVariable UUID customerId,
            @PathVariable UUID endpointId
    ) {
        WebhookEndpointSummary existing = endpointFacade.getEndpoint(endpointId);
        if (!existing.customerId().equals(customerId)) {
            throw new com.meterflow.identity.api.UnauthorizedCustomerAccessException("Endpoint does not belong to customer");
        }
        return endpointFacade.rotateSecret(endpointId);
    }

    @DeleteMapping("/customer/{customerId}/webhooks/endpoints/{endpointId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteCustomerEndpoint(@PathVariable UUID customerId, @PathVariable UUID endpointId) {
        WebhookEndpointSummary existing = endpointFacade.getEndpoint(endpointId);
        if (!existing.customerId().equals(customerId)) {
            throw new com.meterflow.identity.api.UnauthorizedCustomerAccessException("Endpoint does not belong to customer");
        }
        endpointFacade.deleteEndpoint(endpointId);
    }

    @GetMapping("/customer/{customerId}/webhooks/deliveries")
    PageResponse<WebhookDeliverySummary> listCustomerDeliveries(
            @PathVariable UUID customerId,
            @RequestParam(required = false) WebhookDeliveryStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PageResponse.from(deliveryFacade.listCustomerDeliveries(customerId, status, pageable));
    }

    @GetMapping("/customer/{customerId}/webhooks/deliveries/{deliveryId}")
    WebhookDeliveryDetail getCustomerDeliveryDetail(
            @PathVariable UUID customerId,
            @PathVariable UUID deliveryId
    ) {
        WebhookDeliveryDetail detail = deliveryFacade.getDeliveryDetail(deliveryId);
        if (!detail.summary().customerId().equals(customerId)) {
            throw new com.meterflow.identity.api.UnauthorizedCustomerAccessException("Delivery does not belong to customer");
        }
        return detail;
    }

    @PostMapping("/customer/{customerId}/webhooks/deliveries/{deliveryId}/replay")
    WebhookDeliveryDetail replayCustomerDelivery(
            @PathVariable UUID customerId,
            @PathVariable UUID deliveryId
    ) {
        WebhookDeliveryDetail detail = deliveryFacade.getDeliveryDetail(deliveryId);
        if (!detail.summary().customerId().equals(customerId)) {
            throw new com.meterflow.identity.api.UnauthorizedCustomerAccessException("Delivery does not belong to customer");
        }
        return deliveryFacade.replayDelivery(deliveryId);
    }

    @GetMapping("/webhooks/endpoints")
    PageResponse<WebhookEndpointSummary> listAllEndpoints(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PageResponse.from(endpointFacade.listAllEndpoints(pageable));
    }

    @GetMapping("/webhooks/deliveries")
    PageResponse<WebhookDeliverySummary> listAllDeliveries(
            @RequestParam(required = false) WebhookDeliveryStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PageResponse.from(deliveryFacade.listAllDeliveries(status, pageable));
    }

    @GetMapping("/webhooks/deliveries/{deliveryId}")
    WebhookDeliveryDetail getDeliveryDetail(@PathVariable UUID deliveryId) {
        return deliveryFacade.getDeliveryDetail(deliveryId);
    }

    @PostMapping("/webhooks/deliveries/dispatch")
    WebhookDispatchSummary dispatchDueDeliveries() {
        return deliveryFacade.dispatchDueDeliveries();
    }

    @PostMapping("/webhooks/deliveries/{deliveryId}/replay")
    WebhookDeliveryDetail replayDelivery(@PathVariable UUID deliveryId) {
        return deliveryFacade.replayDelivery(deliveryId);
    }

    record RegisterEndpointRequest(
            @NotBlank String name,
            @NotBlank String targetUrl,
            @NotEmpty Set<String> subscribedEvents
    ) {
    }

    record UpdateEndpointRequest(
            String name,
            String targetUrl,
            Set<String> subscribedEvents,
            Boolean active
    ) {
    }
}
