package com.meterflow.identity.internal;

import com.meterflow.identity.api.CustomerMembershipSummary;
import com.meterflow.identity.api.CustomerSummary;
import com.meterflow.identity.api.IdentityManagementFacade;
import com.meterflow.identity.api.IdentityQueryFacade;
import com.meterflow.identity.api.MembershipRole;
import com.meterflow.identity.api.PlatformRole;
import com.meterflow.identity.api.UserSummary;
import com.meterflow.shared.SupportedCurrency;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent, opt-in startup provisioner for the three academic demonstration accounts
 * (Demo Admin, Demo Operator, Demo Customer) and their isolated synthetic demo customer tenant.
 *
 * <p>Uses {@link IdentityManagementFacade} exclusively so all accounts pass standard domain validation
 * and BCrypt hashing. Never duplicates users, never overwrites existing passwords, and never logs secrets.
 */
@Component
@Order(10)
@ConditionalOnProperty(name = "meterflow.demo.enabled", havingValue = "true")
class DemoAccountProvisioningRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoAccountProvisioningRunner.class);

    private final DemoAccountProperties properties;
    private final IdentityQueryFacade identityQueryFacade;
    private final IdentityManagementFacade identityManagementFacade;

    DemoAccountProvisioningRunner(
            DemoAccountProperties properties,
            IdentityQueryFacade identityQueryFacade,
            IdentityManagementFacade identityManagementFacade
    ) {
        this.properties = properties;
        this.identityQueryFacade = identityQueryFacade;
        this.identityManagementFacade = identityManagementFacade;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        provisionDemoAccounts();
    }

    void provisionDemoAccounts() {
        String adminEmail = validateAccountConfig("METERFLOW_DEMO_ADMIN", properties.getAdmin());
        String operatorEmail = validateAccountConfig("METERFLOW_DEMO_OPERATOR", properties.getOperator());
        String customerEmail = validateAccountConfig("METERFLOW_DEMO_CUSTOMER", properties.getCustomer());

        Set<String> uniqueEmails = new HashSet<>(List.of(adminEmail, operatorEmail, customerEmail));
        if (uniqueEmails.size() != 3) {
            throw new IllegalStateException(
                    "Invalid meterflow.demo configuration: Admin, Operator, and Customer demo emails must be distinct"
            );
        }

        ensureUser(adminEmail, properties.getAdmin().getFullName(), properties.getAdmin().getPassword(), PlatformRole.ADMIN);
        ensureUser(operatorEmail, properties.getOperator().getFullName(), properties.getOperator().getPassword(), PlatformRole.OPERATOR);
        UserSummary customerUser = ensureUser(
                customerEmail,
                properties.getCustomer().getFullName(),
                properties.getCustomer().getPassword(),
                PlatformRole.CUSTOMER
        );

        String externalKey = requireNonBlank(properties.getCustomerExternalKey(), "demo-acme-cloud");
        String customerName = requireNonBlank(properties.getCustomerName(), "Acme Cloud Demo");
        CustomerSummary demoCustomer = identityQueryFacade.findCustomerByExternalKey(externalKey)
                .orElseGet(() -> identityManagementFacade.createCustomer(externalKey, customerName, SupportedCurrency.BDT));

        if (customerUser.role() == PlatformRole.CUSTOMER) {
            List<CustomerMembershipSummary> activeMemberships =
                    identityQueryFacade.findActiveMembershipsForUser(customerUser.id());
            boolean alreadyLinked = activeMemberships.stream()
                    .anyMatch(m -> demoCustomer.id().equals(m.customerId()));
            if (!alreadyLinked) {
                identityManagementFacade.assignMembership(customerUser.id(), demoCustomer.id(), MembershipRole.OWNER);
            }
        }

        log.info(
                "Academic demo accounts verified/provisioned idempotently (roles=ADMIN,OPERATOR,CUSTOMER; demoTenant={})",
                demoCustomer.externalKey()
        );
    }

    private UserSummary ensureUser(String normalizedEmail, String fullName, String rawPassword, PlatformRole expectedRole) {
        return identityQueryFacade.findUserByEmail(normalizedEmail)
                .map(existing -> {
                    if (existing.role() != expectedRole) {
                        log.warn(
                                "Existing demo user {} has role {} instead of expected {}; preserving existing account without mutation",
                                existing.email(),
                                existing.role(),
                                expectedRole
                        );
                    }
                    return existing;
                })
                .orElseGet(() -> {
                    String resolvedName = requireNonBlank(fullName, "Demo " + expectedRole.name());
                    return identityManagementFacade.createUser(normalizedEmail, resolvedName, rawPassword, expectedRole);
                });
    }

    private static String validateAccountConfig(String envPrefix, DemoAccountProperties.AccountConfig config) {
        if (config == null) {
            throw new IllegalStateException(
                    "Invalid meterflow.demo configuration: missing account block for " + envPrefix
            );
        }
        try {
            String normalizedEmail = IdentityService.normalizeAndValidateEmail(config.getEmail());
            IdentityService.validateRawPassword(config.getPassword());
            return normalizedEmail;
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "Invalid meterflow.demo configuration for " + envPrefix
                            + ": provide a valid email and a password of at least "
                            + IdentityService.MIN_PASSWORD_LENGTH + " characters via "
                            + envPrefix + "_PASSWORD"
            );
        }
    }

    private static String requireNonBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }
}
