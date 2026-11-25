package com.meterflow.shared;

/**
 * Shared correlation ID HTTP header and MDC logging key constants.
 */
public final class CorrelationIds {

    public static final String HEADER_NAME = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private CorrelationIds() {
    }
}
