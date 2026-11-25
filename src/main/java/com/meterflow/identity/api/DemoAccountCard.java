package com.meterflow.identity.api;

/**
 * Immutable view model for an opt-in academic demonstration account displayed on the sign-in page.
 * Overrides {@link #toString()} to ensure configured credentials are never leaked into logs.
 */
public record DemoAccountCard(
        PlatformRole role,
        String displayName,
        String email,
        String password,
        String description,
        String landingPath
) {
    @Override
    public String toString() {
        return "DemoAccountCard[role=" + role
                + ", displayName=" + displayName
                + ", email=" + email
                + ", password=***]";
    }
}
