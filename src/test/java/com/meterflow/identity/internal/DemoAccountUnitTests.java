package com.meterflow.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.meterflow.identity.api.CustomerMembershipSummary;
import com.meterflow.identity.api.CustomerStatus;
import com.meterflow.identity.api.CustomerSummary;
import com.meterflow.identity.api.DemoAccountCard;
import com.meterflow.identity.api.IdentityManagementFacade;
import com.meterflow.identity.api.IdentityQueryFacade;
import com.meterflow.identity.api.MembershipRole;
import com.meterflow.identity.api.MembershipStatus;
import com.meterflow.identity.api.PlatformRole;
import com.meterflow.identity.api.UserStatus;
import com.meterflow.identity.api.UserSummary;
import com.meterflow.shared.SupportedCurrency;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class DemoAccountUnitTests {

    @Test
    void demoProvisionerCreatesThreeRolesAndDemoTenantOnceAndIsIdempotentOnRestart() {
        DemoAccountProperties props = validDemoProperties();
        IdentityQueryFacade queryFacade = mock(IdentityQueryFacade.class);
        IdentityManagementFacade managementFacade = mock(IdentityManagementFacade.class);

        UUID adminId = UUID.randomUUID();
        UUID operatorId = UUID.randomUUID();
        UUID customerUserId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-26T00:00:00Z");

        UserSummary adminUser = new UserSummary(adminId, "demo-admin@meterflow.demo", "Demo Administrator", PlatformRole.ADMIN, UserStatus.ACTIVE, now);
        UserSummary operatorUser = new UserSummary(operatorId, "demo-operator@meterflow.demo", "Demo Operator", PlatformRole.OPERATOR, UserStatus.ACTIVE, now);
        UserSummary customerUser = new UserSummary(customerUserId, "demo-customer@meterflow.demo", "Demo Customer", PlatformRole.CUSTOMER, UserStatus.ACTIVE, now);
        CustomerSummary demoCustomer = new CustomerSummary(customerId, "demo-acme-cloud", "Acme Cloud Demo", SupportedCurrency.BDT, CustomerStatus.ACTIVE, now);

        when(queryFacade.findUserByEmail("demo-admin@meterflow.demo")).thenReturn(Optional.empty());
        when(queryFacade.findUserByEmail("demo-operator@meterflow.demo")).thenReturn(Optional.empty());
        when(queryFacade.findUserByEmail("demo-customer@meterflow.demo")).thenReturn(Optional.empty());
        when(queryFacade.findCustomerByExternalKey("demo-acme-cloud")).thenReturn(Optional.empty());
        when(queryFacade.findActiveMembershipsForUser(customerUserId)).thenReturn(List.of());

        when(managementFacade.createUser("demo-admin@meterflow.demo", "Demo Administrator", "DemoAdminPass#2026", PlatformRole.ADMIN))
                .thenReturn(adminUser);
        when(managementFacade.createUser("demo-operator@meterflow.demo", "Demo Operator", "DemoOperPass#2026", PlatformRole.OPERATOR))
                .thenReturn(operatorUser);
        when(managementFacade.createUser("demo-customer@meterflow.demo", "Demo Customer", "DemoCustPass#2026", PlatformRole.CUSTOMER))
                .thenReturn(customerUser);
        when(managementFacade.createCustomer("demo-acme-cloud", "Acme Cloud Demo", SupportedCurrency.BDT))
                .thenReturn(demoCustomer);

        DemoAccountProvisioningRunner runner = new DemoAccountProvisioningRunner(props, queryFacade, managementFacade);
        runner.run(new DefaultApplicationArguments());

        verify(managementFacade).createUser("demo-admin@meterflow.demo", "Demo Administrator", "DemoAdminPass#2026", PlatformRole.ADMIN);
        verify(managementFacade).createUser("demo-operator@meterflow.demo", "Demo Operator", "DemoOperPass#2026", PlatformRole.OPERATOR);
        verify(managementFacade).createUser("demo-customer@meterflow.demo", "Demo Customer", "DemoCustPass#2026", PlatformRole.CUSTOMER);
        verify(managementFacade).createCustomer("demo-acme-cloud", "Acme Cloud Demo", SupportedCurrency.BDT);
        verify(managementFacade).assignMembership(customerUserId, customerId, MembershipRole.OWNER);

        // Second boot: accounts, customer, and membership already exist -> zero mutations or password overwrites
        IdentityQueryFacade restartQuery = mock(IdentityQueryFacade.class);
        IdentityManagementFacade restartManagement = mock(IdentityManagementFacade.class);
        when(restartQuery.findUserByEmail("demo-admin@meterflow.demo")).thenReturn(Optional.of(adminUser));
        when(restartQuery.findUserByEmail("demo-operator@meterflow.demo")).thenReturn(Optional.of(operatorUser));
        when(restartQuery.findUserByEmail("demo-customer@meterflow.demo")).thenReturn(Optional.of(customerUser));
        when(restartQuery.findCustomerByExternalKey("demo-acme-cloud")).thenReturn(Optional.of(demoCustomer));
        when(restartQuery.findActiveMembershipsForUser(customerUserId)).thenReturn(List.of(
                new CustomerMembershipSummary(UUID.randomUUID(), customerUserId, customerId, MembershipRole.OWNER, MembershipStatus.ACTIVE, now)
        ));

        DemoAccountProvisioningRunner restartRunner = new DemoAccountProvisioningRunner(props, restartQuery, restartManagement);
        restartRunner.run(new DefaultApplicationArguments());

        verify(restartManagement, never()).createUser(any(), any(), any(), any());
        verify(restartManagement, never()).createCustomer(any(), any(), any());
        verify(restartManagement, never()).assignMembership(any(), any(), eq(MembershipRole.OWNER));
    }

    @Test
    void demoProvisionerFailsSafelyWithoutLeakingPasswordsWhenMisconfigured() {
        DemoAccountProperties weakProps = validDemoProperties();
        String weakSecret = "tooShort1";
        weakProps.getOperator().setPassword(weakSecret);

        DemoAccountProvisioningRunner runner = new DemoAccountProvisioningRunner(
                weakProps,
                mock(IdentityQueryFacade.class),
                mock(IdentityManagementFacade.class)
        );

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("METERFLOW_DEMO_OPERATOR_PASSWORD")
                .hasMessageNotContaining(weakSecret);

        DemoAccountProperties duplicateEmailProps = validDemoProperties();
        duplicateEmailProps.getOperator().setEmail(duplicateEmailProps.getAdmin().getEmail());

        DemoAccountProvisioningRunner duplicateRunner = new DemoAccountProvisioningRunner(
                duplicateEmailProps,
                mock(IdentityQueryFacade.class),
                mock(IdentityManagementFacade.class)
        );

        assertThatThrownBy(() -> duplicateRunner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be distinct");
    }

    @Test
    void demoCatalogHidesAccountsWhenDisabledAndMasksPasswordInToStringWhenEnabled() {
        DemoAccountProperties disabledProps = validDemoProperties();
        disabledProps.setEnabled(false);
        DefaultDemoAccountCatalog disabledCatalog = new DefaultDemoAccountCatalog(disabledProps);

        assertThat(disabledCatalog.isDemoModeEnabled()).isFalse();
        assertThat(disabledCatalog.getDemoAccounts()).isEmpty();

        DemoAccountProperties enabledProps = validDemoProperties();
        DefaultDemoAccountCatalog enabledCatalog = new DefaultDemoAccountCatalog(enabledProps);

        assertThat(enabledCatalog.isDemoModeEnabled()).isTrue();
        List<DemoAccountCard> cards = enabledCatalog.getDemoAccounts();
        assertThat(cards).hasSize(3);
        assertThat(cards).extracting(DemoAccountCard::role)
                .containsExactly(PlatformRole.ADMIN, PlatformRole.OPERATOR, PlatformRole.CUSTOMER);
        assertThat(cards.get(0).toString()).doesNotContain("DemoAdminPass#2026").contains("password=***");
    }

    private static DemoAccountProperties validDemoProperties() {
        DemoAccountProperties props = new DemoAccountProperties();
        props.setEnabled(true);
        props.setCustomerExternalKey("demo-acme-cloud");
        props.setCustomerName("Acme Cloud Demo");
        props.getAdmin().setEmail("demo-admin@meterflow.demo");
        props.getAdmin().setFullName("Demo Administrator");
        props.getAdmin().setPassword("DemoAdminPass#2026");
        props.getOperator().setEmail("demo-operator@meterflow.demo");
        props.getOperator().setFullName("Demo Operator");
        props.getOperator().setPassword("DemoOperPass#2026");
        props.getCustomer().setEmail("demo-customer@meterflow.demo");
        props.getCustomer().setFullName("Demo Customer");
        props.getCustomer().setPassword("DemoCustPass#2026");
        return props;
    }
}
