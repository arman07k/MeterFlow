package com.meterflow.identity.internal;

import com.meterflow.identity.api.DemoAccountCard;
import com.meterflow.identity.api.DemoAccountCatalog;
import com.meterflow.identity.api.PlatformRole;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * Exposes configured opt-in academic demonstration accounts to the sign-in view without leaking secrets in logs.
 */
@Service
@EnableConfigurationProperties(DemoAccountProperties.class)
class DefaultDemoAccountCatalog implements DemoAccountCatalog {

    private final DemoAccountProperties properties;

    DefaultDemoAccountCatalog(DemoAccountProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean isDemoModeEnabled() {
        return properties.isEnabled();
    }

    @Override
    public List<DemoAccountCard> getDemoAccounts() {
        if (!properties.isEnabled()) {
            return List.of();
        }
        return List.of(
                new DemoAccountCard(
                        PlatformRole.ADMIN,
                        "Admin",
                        normalizeEmailOrBlank(properties.getAdmin().getEmail()),
                        safeString(properties.getAdmin().getPassword()),
                        "Full platform access: Operator Console, catalogue publishing, batch billing, payments/dunning, webhooks, audit logs, and OpenAPI docs.",
                        "/operator/overview"
                ),
                new DemoAccountCard(
                        PlatformRole.OPERATOR,
                        "Operator",
                        normalizeEmailOrBlank(properties.getOperator().getEmail()),
                        safeString(properties.getOperator().getPassword()),
                        "Operations console access: customer lookup, subscriptions, draft/final billing runs, mock payments, dunning, webhooks, and staff simulation.",
                        "/operator/overview"
                ),
                new DemoAccountCard(
                        PlatformRole.CUSTOMER,
                        "Customer",
                        normalizeEmailOrBlank(properties.getCustomer().getEmail()),
                        safeString(properties.getCustomer().getPassword()),
                        "Tenant-isolated Customer Portal for " + safeString(properties.getCustomerName())
                                + ": live usage estimates, invoices, read-only payments, credits, webhooks, and published-plan simulation.",
                        "/customer/dashboard"
                )
        );
    }

    @Override
    public String getDemoCustomerExternalKey() {
        return safeString(properties.getCustomerExternalKey());
    }

    private static String normalizeEmailOrBlank(String email) {
        return email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String safeString(String value) {
        return value == null ? "" : value.trim();
    }
}
