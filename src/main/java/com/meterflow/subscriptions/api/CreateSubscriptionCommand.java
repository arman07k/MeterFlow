package com.meterflow.subscriptions.api;

import com.meterflow.shared.BillingInterval;
import com.meterflow.shared.SupportedCurrency;
import java.time.Instant;
import java.util.UUID;

public record CreateSubscriptionCommand(
        UUID customerId,
        UUID planVersionId,
        SupportedCurrency currency,
        BillingInterval billingInterval,
        String billingTimezone,
        Integer seatQuantity,
        Instant startAt,
        Instant trialEndAt,
        TrialConversionPolicy trialConversionPolicy,
        String idempotencyKey
) {
}
