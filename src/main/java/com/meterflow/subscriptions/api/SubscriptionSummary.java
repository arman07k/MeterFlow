package com.meterflow.subscriptions.api;

import com.meterflow.shared.BillingInterval;
import com.meterflow.shared.SupportedCurrency;
import java.time.Instant;
import java.util.UUID;

public record SubscriptionSummary(
        UUID id,
        UUID customerId,
        UUID currentPlanVersionId,
        SupportedCurrency currency,
        BillingInterval billingInterval,
        String billingTimezone,
        int seatQuantity,
        SubscriptionStatus status,
        Instant trialStartAt,
        Instant trialEndAt,
        TrialConversionPolicy trialConversionPolicy,
        Instant currentPeriodStart,
        Instant currentPeriodEnd,
        Instant pausedAt,
        Long pausedRemainingSeconds,
        boolean cancelAtPeriodEnd,
        Instant cancelledAt,
        Instant createdAt,
        Instant updatedAt
) {
}
