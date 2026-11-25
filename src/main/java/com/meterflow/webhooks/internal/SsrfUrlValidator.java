package com.meterflow.webhooks.internal;

import com.meterflow.webhooks.api.InvalidWebhookEndpointException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Validates outbound webhook target URLs against SSRF vectors (ADR-009, FR9).
 */
@Component
class SsrfUrlValidator {

    private final boolean allowLocalTargets;

    @Autowired
    SsrfUrlValidator(WebhookProperties properties) {
        this.allowLocalTargets = properties.isAllowLocalTargets();
    }

    SsrfUrlValidator(boolean allowLocalTargets) {
        this.allowLocalTargets = allowLocalTargets;
    }

    URI validateAndNormalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new InvalidWebhookEndpointException("Webhook target URL must not be blank");
        }
        String trimmed = rawUrl.trim();
        if (trimmed.length() > 1000) {
            throw new InvalidWebhookEndpointException("Webhook target URL exceeds maximum length of 1000 characters");
        }
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException ex) {
            throw new InvalidWebhookEndpointException("Webhook target URL is not a valid URI");
        }
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
        if (!"https".equals(scheme) && !(allowLocalTargets && "http".equals(scheme))) {
            throw new InvalidWebhookEndpointException(
                    "Webhook target URL must use HTTPS"
            );
        }
        if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
            throw new InvalidWebhookEndpointException("Webhook target URL must not contain embedded credentials");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidWebhookEndpointException("Webhook target URL must include a valid host");
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (!allowLocalTargets) {
            rejectForbiddenHostName(normalizedHost);
            validateResolvedAddresses(normalizedHost);
        }
        return uri;
    }

    private void rejectForbiddenHostName(String host) {
        if ("localhost".equals(host)
                || host.endsWith(".localhost")
                || host.endsWith(".local")
                || host.endsWith(".internal")
                || "metadata.google.internal".equals(host)
                || "169.254.169.254".equals(host)) {
            throw new InvalidWebhookEndpointException(
                    "Webhook target host is blocked by SSRF policy: " + host
            );
        }
    }

    private void validateResolvedAddresses(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (isBlockedAddress(address)) {
                    throw new InvalidWebhookEndpointException(
                            "Webhook target resolves to a blocked private, loopback, or link-local address"
                    );
                }
            }
        } catch (UnknownHostException ex) {
            // If host is an unresolvable DNS name in an offline environment, still ensure it is not a numeric/local target.
            if (host.matches("^[0-9.]+$") || host.contains(":")) {
                throw new InvalidWebhookEndpointException("Webhook target IP address is invalid or blocked");
            }
        }
    }

    static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            // Carrier-grade NAT 100.64.0.0/10, benchmark 198.18.0.0/15, 0.0.0.0/8
            if (first == 0 || (first == 100 && second >= 64 && second <= 127)
                    || (first == 198 && (second == 18 || second == 19))) {
                return true;
            }
        } else if (bytes.length == 16) {
            // IPv6 Unique Local Addresses fc00::/7
            int first = bytes[0] & 0xFE;
            if (first == 0xFC) {
                return true;
            }
        }
        return false;
    }
}
