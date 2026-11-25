package com.meterflow.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.meterflow.identity.api.AuthenticatedActor;
import com.meterflow.identity.api.IdentityManagementFacade;
import com.meterflow.identity.api.PlatformRole;
import com.meterflow.identity.api.UserStatus;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class IdentityUnitTests {

    @Test
    void bootstrapAdminCreatesSingleInitialAdminOnceAndNeverReplacesExistingAdmin() {
        UserRepository userRepository = mock(UserRepository.class);
        IdentityManagementFacade managementFacade = mock(IdentityManagementFacade.class);

        when(userRepository.existsByPlatformRole(PlatformRole.ADMIN)).thenReturn(false);
        when(userRepository.existsByEmail("admin@meterflow.io")).thenReturn(false);

        InitialAdminBootstrapRunner runner = new InitialAdminBootstrapRunner(
                userRepository,
                managementFacade,
                "  ADMIN@MeterFlow.IO ",
                "Platform Admin",
                "StrongBootstrapPass#2026"
        );
        runner.run(new DefaultApplicationArguments());

        verify(managementFacade).createUser(
                "admin@meterflow.io",
                "Platform Admin",
                "StrongBootstrapPass#2026",
                PlatformRole.ADMIN
        );

        // Subsequent restart: ADMIN already exists -> must skip without replacing password
        UserRepository existingRepo = mock(UserRepository.class);
        IdentityManagementFacade secondFacade = mock(IdentityManagementFacade.class);
        when(existingRepo.existsByPlatformRole(PlatformRole.ADMIN)).thenReturn(true);

        InitialAdminBootstrapRunner secondRunner = new InitialAdminBootstrapRunner(
                existingRepo,
                secondFacade,
                "admin@meterflow.io",
                "Platform Admin",
                "DifferentPassword#2026"
        );
        secondRunner.run(new DefaultApplicationArguments());

        verify(secondFacade, never()).createUser(
                "admin@meterflow.io",
                "Platform Admin",
                "DifferentPassword#2026",
                PlatformRole.ADMIN
        );
    }

    @Test
    void bootstrapAdminFailsSafelyWithoutLeakingPasswordWhenMisconfigured() {
        UserRepository userRepository = mock(UserRepository.class);
        IdentityManagementFacade managementFacade = mock(IdentityManagementFacade.class);
        when(userRepository.existsByPlatformRole(PlatformRole.ADMIN)).thenReturn(false);

        String weakSecret = "short123";
        InitialAdminBootstrapRunner weakPasswordRunner = new InitialAdminBootstrapRunner(
                userRepository,
                managementFacade,
                "admin@meterflow.io",
                "Admin",
                weakSecret
        );

        assertThatThrownBy(() -> weakPasswordRunner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid meterflow.security.bootstrap-admin configuration")
                .hasMessageNotContaining(weakSecret);

        InitialAdminBootstrapRunner invalidEmailRunner = new InitialAdminBootstrapRunner(
                userRepository,
                managementFacade,
                "not-an-email",
                "Admin",
                "StrongBootstrapPass#2026"
        );

        assertThatThrownBy(() -> invalidEmailRunner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("StrongBootstrapPass#2026");
    }

    @Test
    void authenticatedActorDefensivelyCopiesCustomerIdsAndStatusRulesAreStrict() {
        UUID customerId = UUID.randomUUID();
        Set<UUID> mutable = new HashSet<>();
        mutable.add(customerId);

        AuthenticatedActor actor = new AuthenticatedActor(
                UUID.randomUUID(),
                "customer@example.com",
                "Customer User",
                PlatformRole.CUSTOMER,
                UserStatus.ACTIVE,
                mutable
        );
        mutable.clear();

        assertThat(actor.authorizedCustomerIds()).containsExactly(customerId);
        assertThat(actor.hasCustomerMembership(customerId)).isTrue();
        assertThat(actor.isPlatformStaff()).isFalse();
        assertThat(UserStatus.ACTIVE.canAuthenticate()).isTrue();
        assertThat(UserStatus.DISABLED.canAuthenticate()).isFalse();
        assertThat(UserStatus.SUSPENDED.canAuthenticate()).isFalse();
    }
}
