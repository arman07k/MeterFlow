package com.meterflow.payments.internal;

import com.meterflow.payments.api.InvalidPaymentRequestException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Pure deterministic calculator for exact Day 1 (+24h), Day 3 (+72h), Day 7 (+168h) payment retries
 * and the separate post-exhaustion suspension timer (FR8, ADR-007, ADR-015).
 */
final class DunningScheduleCalculator {

    static final int REQUIRED_RETRY_COUNT = 3;
    static final List<Integer> DEFAULT_RETRY_OFFSETS_HOURS = List.of(24, 72, 168);
    static final int DEFAULT_SUSPENSION_OFFSET_HOURS = 336;

    private DunningScheduleCalculator() {
    }

    static void validateSchedule(List<Integer> retryOffsetsHours, int suspensionOffsetHours) {
        if (retryOffsetsHours == null || retryOffsetsHours.size() != REQUIRED_RETRY_COUNT) {
            throw new InvalidPaymentRequestException(
                    "retryOffsetsHours must contain exactly 3 offsets (Day 1, Day 3, Day 7)");
        }
        int previous = 0;
        for (Integer offset : retryOffsetsHours) {
            if (offset == null || offset <= previous) {
                throw new InvalidPaymentRequestException(
                        "retryOffsetsHours must be strictly increasing positive hour offsets");
            }
            previous = offset;
        }
        if (suspensionOffsetHours < previous) {
            throw new InvalidPaymentRequestException(
                    "suspensionOffsetHours must be >= the final retry offset (" + previous + "h)");
        }
    }

    static Optional<Instant> computeNextRetryAt(
            Instant initialFailureAt,
            int retriesCompleted,
            List<Integer> retryOffsetsHours
    ) {
        if (initialFailureAt == null) {
            throw new InvalidPaymentRequestException("initialFailureAt must not be null");
        }
        if (retriesCompleted < 0 || retriesCompleted >= REQUIRED_RETRY_COUNT) {
            return Optional.empty();
        }
        int offsetHours = retryOffsetsHours.get(retriesCompleted);
        return Optional.of(initialFailureAt.plus(Duration.ofHours(offsetHours)).truncatedTo(ChronoUnit.MICROS));
    }

    static Instant computeSuspensionAt(Instant initialFailureAt, int suspensionOffsetHours) {
        if (initialFailureAt == null) {
            throw new InvalidPaymentRequestException("initialFailureAt must not be null");
        }
        return initialFailureAt.plus(Duration.ofHours(suspensionOffsetHours)).truncatedTo(ChronoUnit.MICROS);
    }
}
