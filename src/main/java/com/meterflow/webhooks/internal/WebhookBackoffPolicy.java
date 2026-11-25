package com.meterflow.webhooks.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Exponential backoff and HTTP status classification policy for outbound webhooks (ADR-009).
 *
 * <p>Maximum 6 attempts total: initial delivery + 5 retries scheduled at +30s, +2m, +10m, +1h, +6h.
 */
final class WebhookBackoffPolicy {

    static final int MAX_ATTEMPTS = 6;
    static final List<Duration> RETRY_DELAYS = List.of(
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            Duration.ofMinutes(10),
            Duration.ofHours(1),
            Duration.ofHours(6)
    );

    private WebhookBackoffPolicy() {
    }

    static boolean isSuccessStatus(Integer httpStatus) {
        return httpStatus != null && httpStatus >= 200 && httpStatus < 300;
    }

    static boolean isRetryableFailure(Integer httpStatus) {
        if (httpStatus == null) {
            return true; // Connection timeout / transport failure
        }
        return httpStatus == 408 || httpStatus == 429 || httpStatus >= 500;
    }

    /**
     * Computes the next scheduled retry timestamp after a failed attempt, or empty if exhausted.
     */
    static Optional<Instant> nextRetryAt(int completedAttemptNumber, int maxAttempts, Instant attemptTime) {
        int effectiveMax = Math.max(1, maxAttempts);
        if (completedAttemptNumber >= effectiveMax) {
            return Optional.empty();
        }
        int delayIndex = Math.min(Math.max(0, completedAttemptNumber - 1), RETRY_DELAYS.size() - 1);
        return Optional.of(attemptTime.plus(RETRY_DELAYS.get(delayIndex)));
    }
}
