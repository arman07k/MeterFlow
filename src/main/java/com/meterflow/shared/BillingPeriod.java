package com.meterflow.shared;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Immutable UTC billing period anchored in a configured IANA timezone (ADR-012).
 *
 * <p>Calendar generation uses {@link ZonedDateTime#plusMonths(long)} / {@link ZonedDateTime#plusYears(long)}
 * so variable-length months (Jan 31 -> Feb 28/29), leap years (Feb 29 -> Feb 28), and DST transitions
 * preserve local billing-anchor dates while persisting and computing exact UTC instants/seconds.
 */
public record BillingPeriod(
        Instant start,
        Instant end,
        BillingInterval interval,
        ZoneId timezone
) {

    public BillingPeriod {
        Objects.requireNonNull(start, "period start must not be null");
        Objects.requireNonNull(end, "period end must not be null");
        Objects.requireNonNull(interval, "billing interval must not be null");
        Objects.requireNonNull(timezone, "billing timezone must not be null");
        start = start.truncatedTo(ChronoUnit.MICROS);
        end = end.truncatedTo(ChronoUnit.MICROS);
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("Billing period end must be strictly after start");
        }
    }

    public static BillingPeriod startingAt(Instant startUtc, BillingInterval interval, ZoneId timezone) {
        Objects.requireNonNull(startUtc, "startUtc must not be null");
        Objects.requireNonNull(interval, "interval must not be null");
        Objects.requireNonNull(timezone, "timezone must not be null");

        Instant normalizedStart = startUtc.truncatedTo(ChronoUnit.MICROS);
        ZonedDateTime zonedStart = normalizedStart.atZone(timezone);
        ZonedDateTime zonedEnd = switch (interval) {
            case MONTHLY -> zonedStart.plusMonths(1);
            case ANNUAL -> zonedStart.plusYears(1);
        };
        return new BillingPeriod(normalizedStart, zonedEnd.toInstant(), interval, timezone);
    }

    public static ZoneId parseTimezone(String timezoneId) {
        if (timezoneId == null || timezoneId.isBlank()) {
            return ZoneId.of("UTC");
        }
        try {
            return ZoneId.of(timezoneId.trim());
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid IANA billing timezone: '" + timezoneId + "'", ex);
        }
    }

    public long totalSeconds() {
        return Duration.between(start, end).getSeconds();
    }

    public long remainingSecondsFrom(Instant effectiveAt) {
        Objects.requireNonNull(effectiveAt, "effectiveAt must not be null");
        if (effectiveAt.isBefore(start) || effectiveAt.isAfter(end)) {
            throw new IllegalArgumentException("effectiveAt must fall within [periodStart, periodEnd]");
        }
        return Duration.between(effectiveAt, end).getSeconds();
    }

    public long elapsedSecondsUntil(Instant effectiveAt) {
        return totalSeconds() - remainingSecondsFrom(effectiveAt);
    }
}
