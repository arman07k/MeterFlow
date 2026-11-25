package com.meterflow.metering.internal;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Computes a deterministic 64-char SHA-256 canonical digest of usage event fields so idempotency
 * key reuse with materially different payloads is reliably detected regardless of JSON key order
 * or trailing decimal zeroes (FR1, ADR-013).
 */
final class CanonicalPayloadHasher {

    private static final HexFormat HEX = HexFormat.of();
    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", ex);
        }
    });

    private CanonicalPayloadHasher() {
    }

    static String computeHash(
            UUID customerId,
            UUID metricId,
            UUID subscriptionId,
            BigDecimal quantity,
            Instant eventTimestamp,
            Map<String, Object> properties
    ) {
        StringBuilder sb = new StringBuilder(192);
        sb.append("c=").append(customerId == null ? "null" : customerId);
        sb.append("|m=").append(metricId == null ? "null" : metricId);
        sb.append("|s=").append(subscriptionId == null ? "null" : subscriptionId);
        sb.append("|q=").append(normalizeDecimal(quantity));
        sb.append("|t=").append(eventTimestamp == null ? "null" : eventTimestamp.truncatedTo(ChronoUnit.MICROS));
        sb.append("|p=");
        appendCanonicalValue(sb, properties == null ? Map.of() : properties);

        MessageDigest digest = SHA256.get();
        digest.reset();
        byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
        return HEX.formatHex(hash);
    }

    private static String normalizeDecimal(BigDecimal value) {
        if (value == null) {
            return "null";
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.compareTo(BigDecimal.ZERO) == 0 ? "0" : stripped.toPlainString();
    }

    private static void appendCanonicalValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendQuoted(sb, entry.getKey());
                sb.append(':');
                appendCanonicalValue(sb, entry.getValue());
            }
            sb.append('}');
            return;
        }
        if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                appendCanonicalValue(sb, list.get(i));
            }
            sb.append(']');
            return;
        }
        if (value instanceof BigDecimal bd) {
            sb.append(normalizeDecimal(bd));
            return;
        }
        if (value instanceof Number num) {
            try {
                sb.append(normalizeDecimal(new BigDecimal(num.toString())));
            } catch (NumberFormatException ex) {
                appendQuoted(sb, num.toString());
            }
            return;
        }
        if (value instanceof Boolean bool) {
            sb.append(bool);
            return;
        }
        appendQuoted(sb, String.valueOf(value));
    }

    private static void appendQuoted(StringBuilder sb, String text) {
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
    }
}
