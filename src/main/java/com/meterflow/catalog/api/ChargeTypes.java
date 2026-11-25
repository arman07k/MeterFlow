package com.meterflow.catalog.api;

import java.util.Set;

/**
 * Canonical identifiers for the six mandatory FR3 charge types.
 * Charge types use String identifiers so new Open/Closed strategies can be registered
 * without modifying existing enums or pricing classes (ADR-001, ADR-011).
 */
public final class ChargeTypes {

    public static final String FLAT_FEE = "FLAT_FEE";
    public static final String PER_UNIT = "PER_UNIT";
    public static final String GRADUATED_TIERS = "GRADUATED_TIERS";
    public static final String VOLUME_TIERS = "VOLUME_TIERS";
    public static final String PACKAGE = "PACKAGE";
    public static final String PER_SEAT = "PER_SEAT";

    public static final Set<String> MANDATORY_TYPES = Set.of(
            FLAT_FEE,
            PER_UNIT,
            GRADUATED_TIERS,
            VOLUME_TIERS,
            PACKAGE,
            PER_SEAT
    );

    private ChargeTypes() {
    }
}
