package com.meterflow.metering.internal;

import com.meterflow.metering.api.LateClassification;
import com.meterflow.subscriptions.api.SubscriptionSummary;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/**
 * Pure deterministic FR1 late-event & future-timestamp policy evaluator (ADR-013).
 *
 * <p>Reuses {@link SubscriptionSummary} billing-period boundaries without duplicating period math.
 */
final class LateEventPolicyEvaluator {

    static final Duration MAX_LATE_WINDOW = Duration.ofHours(72);
    static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);
    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3_600L);

    private LateEventPolicyEvaluator() {
    }

    enum Decision {
        ACCEPT_ON_TIME,
        ACCEPT_LATE_WITHIN_72H,
        REJECT_FUTURE_TIMESTAMP,
        REJECT_LATE_WINDOW_EXCEEDED
    }

    record Evaluation(
            Decision decision,
            LateClassification classification,
            Instant closedPeriodStart,
            Instant closedPeriodEnd,
            BigDecimal hoursLate,
            Instant billingPeriodStart,
            Instant billingPeriodEnd,
            String rejectionReason
    ) {
    }

    static Evaluation evaluate(
            Instant eventTimestamp,
            Instant ingestedAt,
            SubscriptionSummary subscription
    ) {
        if (eventTimestamp.isAfter(ingestedAt.plus(MAX_FUTURE_SKEW))) {
            return new Evaluation(
                    Decision.REJECT_FUTURE_TIMESTAMP,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "Event timestamp exceeds maximum permitted future skew of 5 minutes"
            );
        }

        // Case 1: Subscription has a closed prior period boundary (currentPeriodStart) and event precedes it
        if (subscription != null && subscription.currentPeriodStart() != null
                && eventTimestamp.isBefore(subscription.currentPeriodStart())) {
            Instant closedPeriodEnd = subscription.currentPeriodStart();
            if (ingestedAt.isAfter(closedPeriodEnd.plus(MAX_LATE_WINDOW))) {
                return new Evaluation(
                        Decision.REJECT_LATE_WINDOW_EXCEEDED,
                        null,
                        null,
                        closedPeriodEnd,
                        computeHoursLate(closedPeriodEnd, ingestedAt),
                        null,
                        closedPeriodEnd,
                        "Late event arrived more than 72 hours after billing period closed at " + closedPeriodEnd
                );
            }
            return new Evaluation(
                    Decision.ACCEPT_LATE_WITHIN_72H,
                    LateClassification.LATE_WITHIN_72H,
                    null,
                    closedPeriodEnd,
                    computeHoursLate(closedPeriodEnd, ingestedAt),
                    null,
                    null,
                    null
            );
        }

        // Case 2: Subscription is terminal and its final period already closed before ingestedAt
        if (subscription != null
                && subscription.status().isTerminal()
                && subscription.currentPeriodEnd() != null
                && ingestedAt.isAfter(subscription.currentPeriodEnd())
                && eventTimestamp.isBefore(subscription.currentPeriodEnd())) {
            Instant closedPeriodEnd = subscription.currentPeriodEnd();
            if (ingestedAt.isAfter(closedPeriodEnd.plus(MAX_LATE_WINDOW))) {
                return new Evaluation(
                        Decision.REJECT_LATE_WINDOW_EXCEEDED,
                        null,
                        subscription.currentPeriodStart(),
                        closedPeriodEnd,
                        computeHoursLate(closedPeriodEnd, ingestedAt),
                        subscription.currentPeriodStart(),
                        closedPeriodEnd,
                        "Late event arrived more than 72 hours after billing period closed at " + closedPeriodEnd
                );
            }
            return new Evaluation(
                    Decision.ACCEPT_LATE_WITHIN_72H,
                    LateClassification.LATE_WITHIN_72H,
                    subscription.currentPeriodStart(),
                    closedPeriodEnd,
                    computeHoursLate(closedPeriodEnd, ingestedAt),
                    subscription.currentPeriodStart(),
                    closedPeriodEnd,
                    null
            );
        }

        // Case 3: Open billing period or pre-subscription event — enforce 72h maximum ingestion lag
        if (ingestedAt.isAfter(eventTimestamp.plus(MAX_LATE_WINDOW))) {
            return new Evaluation(
                    Decision.REJECT_LATE_WINDOW_EXCEEDED,
                    null,
                    null,
                    null,
                    computeHoursLate(eventTimestamp, ingestedAt),
                    subscription != null ? subscription.currentPeriodStart() : null,
                    subscription != null ? subscription.currentPeriodEnd() : null,
                    "Event timestamp is older than the 72-hour maximum late-event window"
            );
        }

        Instant periodStart = subscription != null ? subscription.currentPeriodStart() : null;
        Instant periodEnd = subscription != null ? subscription.currentPeriodEnd() : null;
        return new Evaluation(
                Decision.ACCEPT_ON_TIME,
                LateClassification.ON_TIME,
                null,
                null,
                BigDecimal.ZERO,
                periodStart,
                periodEnd,
                null
        );
    }

    private static BigDecimal computeHoursLate(Instant referenceBoundary, Instant ingestedAt) {
        if (!ingestedAt.isAfter(referenceBoundary)) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        long seconds = Duration.between(referenceBoundary, ingestedAt).getSeconds();
        return BigDecimal.valueOf(seconds).divide(SECONDS_PER_HOUR, 4, RoundingMode.HALF_UP);
    }
}
