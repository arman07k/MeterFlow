package com.meterflow.webhooks.api;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Constant-time HMAC-SHA256 webhook signature computation and verification utility (ADR-009, FR9).
 *
 * <p>Signature header format: {@code t=<unix_epoch_seconds>,v1=<hex_hmac>[,v1=<previous_hex_hmac>]}
 * over canonical signed string {@code <epoch_seconds>.<raw_utf8_payload>}.
 */
public final class WebhookSignatureVerifier {

    public static final String SIGNATURE_HEADER = "X-MeterFlow-Signature";
    public static final String DELIVERY_ID_HEADER = "X-MeterFlow-Delivery-Id";
    public static final String EVENT_TYPE_HEADER = "X-MeterFlow-Event-Type";
    public static final String EVENT_VERSION_HEADER = "X-MeterFlow-Event-Version";
    public static final String IDEMPOTENCY_KEY_HEADER = "X-MeterFlow-Idempotency-Key";
    public static final long DEFAULT_MAX_SKEW_SECONDS = 300L;

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final HexFormat HEX_FORMAT = HexFormat.of();

    private WebhookSignatureVerifier() {
    }

    public static String computeSignature(String secret, long epochSeconds, String rawPayload) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Webhook signing secret must not be blank");
        }
        String payload = rawPayload != null ? rawPayload : "";
        String signedContent = epochSeconds + "." + payload;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(signedContent.getBytes(StandardCharsets.UTF_8));
            return HEX_FORMAT.formatHex(digest);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to compute HMAC-SHA256 webhook signature", ex);
        }
    }

    public static String buildSignatureHeader(long epochSeconds, List<String> signatures) {
        if (signatures == null || signatures.isEmpty()) {
            throw new IllegalArgumentException("At least one signature is required");
        }
        StringBuilder builder = new StringBuilder();
        builder.append("t=").append(epochSeconds);
        for (String signature : signatures) {
            if (signature != null && !signature.isBlank()) {
                builder.append(",v1=").append(signature);
            }
        }
        return builder.toString();
    }

    public static boolean verifySignature(
            String signatureHeader,
            String rawPayload,
            List<String> candidateSecrets,
            Instant referenceTime
    ) {
        return verifySignature(
                signatureHeader,
                rawPayload,
                candidateSecrets,
                referenceTime,
                DEFAULT_MAX_SKEW_SECONDS
        );
    }

    public static boolean verifySignature(
            String signatureHeader,
            String rawPayload,
            List<String> candidateSecrets,
            Instant referenceTime,
            long maxSkewSeconds
    ) {
        if (signatureHeader == null || signatureHeader.isBlank()
                || candidateSecrets == null || candidateSecrets.isEmpty()
                || referenceTime == null || maxSkewSeconds < 0) {
            return false;
        }
        Long parsedTimestamp = null;
        List<String> headerSignatures = new ArrayList<>();
        for (String part : signatureHeader.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("t=")) {
                try {
                    parsedTimestamp = Long.parseLong(trimmed.substring(2).trim());
                } catch (NumberFormatException ex) {
                    return false;
                }
            } else if (trimmed.startsWith("v1=")) {
                String value = trimmed.substring(3).trim();
                if (!value.isEmpty()) {
                    headerSignatures.add(value);
                }
            }
        }
        if (parsedTimestamp == null || headerSignatures.isEmpty()) {
            return false;
        }
        long skew = Math.abs(referenceTime.getEpochSecond() - parsedTimestamp);
        if (skew > maxSkewSeconds) {
            return false;
        }
        for (String secret : candidateSecrets) {
            if (secret == null || secret.isBlank()) {
                continue;
            }
            byte[] expectedBytes = computeSignature(secret, parsedTimestamp, rawPayload)
                    .getBytes(StandardCharsets.UTF_8);
            for (String candidateHeaderSig : headerSignatures) {
                byte[] actualBytes = candidateHeaderSig.getBytes(StandardCharsets.UTF_8);
                if (MessageDigest.isEqual(expectedBytes, actualBytes)) {
                    return true;
                }
            }
        }
        return false;
    }
}
