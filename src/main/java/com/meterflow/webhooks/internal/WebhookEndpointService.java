package com.meterflow.webhooks.internal;

import com.meterflow.identity.api.CustomerAccessGuard;
import com.meterflow.identity.api.IdentityQueryFacade;
import com.meterflow.identity.api.PlatformRole;
import com.meterflow.webhooks.api.InvalidWebhookEndpointException;
import com.meterflow.webhooks.api.RegisterWebhookEndpointCommand;
import com.meterflow.webhooks.api.UpdateWebhookEndpointCommand;
import com.meterflow.webhooks.api.WebhookEndpointFacade;
import com.meterflow.webhooks.api.WebhookEndpointSummary;
import com.meterflow.webhooks.api.WebhookEventTypes;
import com.meterflow.webhooks.api.WebhookResourceNotFoundException;
import com.meterflow.webhooks.api.WebhookSecretDisclosure;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class WebhookEndpointService implements WebhookEndpointFacade {

    private final WebhookEndpointRepository endpointRepository;
    private final IdentityQueryFacade identityQueryFacade;
    private final CustomerAccessGuard accessGuard;
    private final SsrfUrlValidator urlValidator;
    private final WebhookSecretCipher secretCipher;
    private final WebhookProperties properties;
    private final Clock clock;

    WebhookEndpointService(
            WebhookEndpointRepository endpointRepository,
            IdentityQueryFacade identityQueryFacade,
            CustomerAccessGuard accessGuard,
            SsrfUrlValidator urlValidator,
            WebhookSecretCipher secretCipher,
            WebhookProperties properties,
            Clock clock
    ) {
        this.endpointRepository = endpointRepository;
        this.identityQueryFacade = identityQueryFacade;
        this.accessGuard = accessGuard;
        this.urlValidator = urlValidator;
        this.secretCipher = secretCipher;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public WebhookSecretDisclosure registerEndpoint(RegisterWebhookEndpointCommand command) {
        if (command == null || command.customerId() == null) {
            throw new InvalidWebhookEndpointException("customerId is required to register a webhook endpoint");
        }
        accessGuard.requireCustomerAccess(command.customerId());
        if (identityQueryFacade.findCustomerById(command.customerId()).isEmpty()) {
            throw new WebhookResourceNotFoundException("Customer not found: " + command.customerId());
        }

        String normalizedName = normalizeName(command.name());
        URI validatedUri = urlValidator.validateAndNormalize(command.targetUrl());
        List<String> validatedEvents = normalizeEvents(command.subscribedEvents());

        String plaintextSecret = secretCipher.generateSigningSecret();
        String encryptedSecret = secretCipher.encrypt(plaintextSecret);
        String prefix = secretCipher.safePrefix(plaintextSecret);
        Instant now = nowUtc();

        WebhookEndpointEntity saved = endpointRepository.saveAndFlush(new WebhookEndpointEntity(
                command.customerId(),
                normalizedName,
                validatedUri.toString(),
                validatedEvents,
                encryptedSecret,
                prefix,
                now
        ));
        return new WebhookSecretDisclosure(
                WebhookMapper.toEndpointSummary(saved, now),
                plaintextSecret,
                null
        );
    }

    @Override
    @Transactional
    public WebhookEndpointSummary updateEndpoint(UUID endpointId, UpdateWebhookEndpointCommand command) {
        if (endpointId == null || command == null) {
            throw new InvalidWebhookEndpointException("endpointId and update command are required");
        }
        WebhookEndpointEntity endpoint = loadActiveOrInactiveEndpoint(endpointId);
        accessGuard.requireCustomerAccess(endpoint.getCustomerId());

        String normalizedName = command.name() != null ? normalizeName(command.name()) : null;
        String normalizedUrl = command.targetUrl() != null
                ? urlValidator.validateAndNormalize(command.targetUrl()).toString()
                : null;
        List<String> normalizedEvents = command.subscribedEvents() != null
                ? normalizeEvents(command.subscribedEvents())
                : null;

        Instant now = nowUtc();
        endpoint.updateConfiguration(normalizedName, normalizedUrl, normalizedEvents, command.active(), now);
        return WebhookMapper.toEndpointSummary(endpointRepository.saveAndFlush(endpoint), now);
    }

    @Override
    @Transactional
    public WebhookSecretDisclosure rotateSecret(UUID endpointId) {
        WebhookEndpointEntity endpoint = loadActiveOrInactiveEndpoint(endpointId);
        accessGuard.requireCustomerAccess(endpoint.getCustomerId());

        Instant now = nowUtc();
        int graceHours = Math.max(1, properties.getRotationGraceHours());
        Instant expiresAt = now.plus(graceHours, ChronoUnit.HOURS);

        String newSecret = secretCipher.generateSigningSecret();
        String newEncrypted = secretCipher.encrypt(newSecret);
        String newPrefix = secretCipher.safePrefix(newSecret);

        endpoint.rotateSecret(newEncrypted, newPrefix, expiresAt, now);
        WebhookEndpointEntity saved = endpointRepository.saveAndFlush(endpoint);
        return new WebhookSecretDisclosure(
                WebhookMapper.toEndpointSummary(saved, now),
                newSecret,
                expiresAt
        );
    }

    @Override
    @Transactional
    public void deleteEndpoint(UUID endpointId) {
        WebhookEndpointEntity endpoint = loadActiveOrInactiveEndpoint(endpointId);
        accessGuard.requireCustomerAccess(endpoint.getCustomerId());
        endpoint.softDelete(nowUtc());
        endpointRepository.saveAndFlush(endpoint);
    }

    @Override
    @Transactional(readOnly = true)
    public WebhookEndpointSummary getEndpoint(UUID endpointId) {
        WebhookEndpointEntity endpoint = loadActiveOrInactiveEndpoint(endpointId);
        accessGuard.requireCustomerAccess(endpoint.getCustomerId());
        return WebhookMapper.toEndpointSummary(endpoint, nowUtc());
    }

    @Override
    @Transactional(readOnly = true)
    public List<WebhookEndpointSummary> listCustomerEndpoints(UUID customerId) {
        accessGuard.requireCustomerAccess(customerId);
        Instant now = nowUtc();
        return endpointRepository.findByCustomerIdAndDeletedAtIsNullOrderByCreatedAtDesc(customerId)
                .stream()
                .map(entity -> WebhookMapper.toEndpointSummary(entity, now))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<WebhookEndpointSummary> listAllEndpoints(Pageable pageable) {
        accessGuard.requireAnyRole(PlatformRole.ADMIN, PlatformRole.OPERATOR);
        Instant now = nowUtc();
        return endpointRepository.findByDeletedAtIsNullOrderByCreatedAtDesc(pageable)
                .map(entity -> WebhookMapper.toEndpointSummary(entity, now));
    }

    private WebhookEndpointEntity loadActiveOrInactiveEndpoint(UUID endpointId) {
        if (endpointId == null) {
            throw new WebhookResourceNotFoundException("Webhook endpoint ID must not be null");
        }
        return endpointRepository.findByIdAndDeletedAtIsNull(endpointId)
                .orElseThrow(() -> new WebhookResourceNotFoundException("Webhook endpoint not found"));
    }

    private static String normalizeName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new InvalidWebhookEndpointException("Webhook endpoint name must not be blank");
        }
        String trimmed = rawName.trim();
        if (trimmed.length() > 120) {
            throw new InvalidWebhookEndpointException("Webhook endpoint name must not exceed 120 characters");
        }
        return trimmed;
    }

    private static List<String> normalizeEvents(Set<String> rawEvents) {
        if (rawEvents == null || rawEvents.isEmpty()) {
            throw new InvalidWebhookEndpointException("At least one subscribed webhook event type is required");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String event : rawEvents) {
            if (event == null || !WebhookEventTypes.isSupported(event)) {
                throw new InvalidWebhookEndpointException("Unsupported webhook event type: " + event);
            }
            normalized.add(event.trim());
        }
        List<String> sorted = new ArrayList<>(normalized);
        sorted.sort(Comparator.naturalOrder());
        return List.copyOf(sorted);
    }

    private Instant nowUtc() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
