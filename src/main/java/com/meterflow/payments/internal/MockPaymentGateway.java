package com.meterflow.payments.internal;

import com.meterflow.payments.api.MockPaymentOutcome;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Deterministic internal mock payment processor (FR8, ADR-007, ADR-015).
 * Clearly isolated from real payment gateways — zero external API calls or SDKs.
 */
@Component
class MockPaymentGateway {

    private final MockPaymentOutcome defaultOutcome;

    MockPaymentGateway(
            @Value("${meterflow.payments.mock.default-outcome:SIMULATE_SUCCESS}") MockPaymentOutcome defaultOutcome
    ) {
        this.defaultOutcome = defaultOutcome == null ? MockPaymentOutcome.SIMULATE_SUCCESS : defaultOutcome;
    }

    MockPaymentOutcome resolveOutcome(MockPaymentOutcome requestedOutcome) {
        return requestedOutcome != null ? requestedOutcome : defaultOutcome;
    }

    MockPaymentOutcome defaultOutcome() {
        return defaultOutcome;
    }
}
