package com.meterflow.billing.api;

public enum InvoiceLineType {
    RECURRING_CHARGE,
    USAGE_CHARGE,
    MINIMUM_COMMITMENT_TRUE_UP,
    PRORATION_ADJUSTMENT,
    LATE_USAGE_ADJUSTMENT,
    MANUAL_ADJUSTMENT
}
