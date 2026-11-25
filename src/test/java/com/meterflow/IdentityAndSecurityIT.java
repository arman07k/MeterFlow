package com.meterflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meterflow.identity.api.CustomerAccessGuard;
import com.meterflow.identity.api.CustomerMembershipSummary;
import com.meterflow.identity.api.CustomerStatus;
import com.meterflow.identity.api.CustomerSummary;
import com.meterflow.identity.api.DuplicateCustomerKeyException;
import com.meterflow.identity.api.DuplicateEmailException;
import com.meterflow.identity.api.IdentityManagementFacade;
import com.meterflow.identity.api.IdentityQueryFacade;
import com.meterflow.identity.api.IdentityResourceNotFoundException;
import com.meterflow.identity.api.InvalidMembershipException;
import com.meterflow.identity.api.MembershipRole;
import com.meterflow.identity.api.MembershipStatus;
import com.meterflow.identity.api.PlatformRole;
import com.meterflow.identity.api.UnauthorizedCustomerAccessException;
import com.meterflow.identity.api.UserStatus;
import com.meterflow.identity.api.UserSummary;
import com.meterflow.shared.SupportedCurrency;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({
        IdentityAndSecurityIT.PostgresContainerConfiguration.class,
        IdentityAndSecurityIT.TenantIsolationTestController.class
})
class IdentityAndSecurityIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresContainerConfiguration {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer("postgres:17-alpine");
        }
    }

    @RestController
    static class TenantIsolationTestController {

        private final CustomerAccessGuard accessGuard;

        TenantIsolationTestController(CustomerAccessGuard accessGuard) {
            this.accessGuard = accessGuard;
        }

        @GetMapping("/api/v1/customer/{customerId}/summary")
        ResponseEntity<String> customerScopedSummary(@PathVariable UUID customerId) {
            accessGuard.requireCustomerAccess(customerId);
            return ResponseEntity.ok("allowed:" + customerId);
        }

        @GetMapping("/api/v1/admin/ping")
        ResponseEntity<String> adminPing() {
            accessGuard.requireAnyRole(PlatformRole.ADMIN);
            return ResponseEntity.ok("admin-pong");
        }

        @GetMapping("/api/v1/operator/ping")
        ResponseEntity<String> operatorPing() {
            accessGuard.requireAnyRole(PlatformRole.ADMIN, PlatformRole.OPERATOR);
            return ResponseEntity.ok("operator-pong");
        }
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private IdentityManagementFacade identityManagement;

    @Autowired
    private IdentityQueryFacade identityQuery;

    @Autowired
    private CustomerAccessGuard customerAccessGuard;

    @Autowired
    private UserDetailsService userDetailsService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        jdbcTemplate.execute("DELETE FROM customer_memberships");
        jdbcTemplate.execute("DELETE FROM users");
        jdbcTemplate.execute("DELETE FROM customers");
    }

    @Test
    void enforcesDatabaseConstraintsEmailNormalizationMembershipsAndTransactionRollback() {
        CustomerSummary acme = identityManagement.createCustomer("cust-acme", "Acme Corp", SupportedCurrency.BDT);
        CustomerSummary beta = identityManagement.createCustomer("cust-beta", "Beta LLC", SupportedCurrency.USD);

        assertThat(acme.currency()).isEqualTo(SupportedCurrency.BDT);
        assertThat(beta.currency()).isEqualTo(SupportedCurrency.USD);
        assertThat(identityQuery.findCustomerByExternalKey("cust-acme")).contains(acme);

        assertThatThrownBy(() -> identityManagement.createCustomer("cust-acme", "Duplicate", SupportedCurrency.BDT))
                .isInstanceOf(DuplicateCustomerKeyException.class);

        UserSummary customerUser = identityManagement.createUser(
                "  Alice.Rahman@Acme.Example  ",
                "Alice Rahman",
                "StrongCustomerPass#2026",
                PlatformRole.CUSTOMER
        );
        assertThat(customerUser.email()).isEqualTo("alice.rahman@acme.example");
        assertThat(identityQuery.findUserByEmail("ALICE.RAHMAN@ACME.EXAMPLE")).contains(customerUser);

        // Verify password stored as BCrypt hash, never plaintext
        String storedHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE id = ?",
                String.class,
                customerUser.id()
        );
        assertThat(storedHash).startsWith("$2a$").doesNotContain("StrongCustomerPass#2026");

        // Duplicate normalized email rejected
        assertThatThrownBy(() -> identityManagement.createUser(
                "ALICE.RAHMAN@ACME.EXAMPLE",
                "Duplicate Alice",
                "AnotherStrongPass#2026",
                PlatformRole.CUSTOMER
        )).isInstanceOf(DuplicateEmailException.class);

        // Membership assignment, lookup, revocation, and reactivation
        CustomerMembershipSummary membership = identityManagement.assignMembership(
                customerUser.id(),
                acme.id(),
                MembershipRole.OWNER
        );
        assertThat(membership.status()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(identityQuery.findAuthorizedCustomerIds(customerUser.id())).containsExactly(acme.id());
        assertThat(identityQuery.findActiveMembershipsForUser(customerUser.id())).hasSize(1);

        CustomerMembershipSummary revoked = identityManagement.revokeMembership(customerUser.id(), acme.id());
        assertThat(revoked.status()).isEqualTo(MembershipStatus.REVOKED);
        assertThat(identityQuery.findAuthorizedCustomerIds(customerUser.id())).isEmpty();

        CustomerMembershipSummary reactivated = identityManagement.assignMembership(
                customerUser.id(),
                acme.id(),
                MembershipRole.BILLING_ADMIN
        );
        assertThat(reactivated.id()).isEqualTo(membership.id());
        assertThat(reactivated.status()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(reactivated.membershipRole()).isEqualTo(MembershipRole.BILLING_ADMIN);

        // Invalid memberships: non-CUSTOMER user or CLOSED customer or missing resource
        UserSummary operator = identityManagement.createUser(
                "ops@meterflow.example",
                "Ops User",
                "StrongOperatorPass#2026",
                PlatformRole.OPERATOR
        );
        assertThatThrownBy(() -> identityManagement.assignMembership(operator.id(), acme.id(), MembershipRole.MEMBER))
                .isInstanceOf(InvalidMembershipException.class);

        identityManagement.updateCustomerStatus(beta.id(), CustomerStatus.CLOSED);
        assertThatThrownBy(() -> identityManagement.assignMembership(customerUser.id(), beta.id(), MembershipRole.MEMBER))
                .isInstanceOf(InvalidMembershipException.class);

        assertThatThrownBy(() -> identityManagement.assignMembership(UUID.randomUUID(), acme.id(), MembershipRole.MEMBER))
                .isInstanceOf(IdentityResourceNotFoundException.class);

        // Direct DB foreign key integrity check
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO customer_memberships (user_id, customer_id, membership_role, status) VALUES (?, ?, 'MEMBER', 'ACTIVE')",
                UUID.randomUUID(),
                acme.id()
        )).isInstanceOf(DataIntegrityViolationException.class);

        // Transaction rollback verification
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status -> {
            identityManagement.createCustomer("cust-rollback", "Rollback Corp", SupportedCurrency.BDT);
            throw new IllegalStateException("Forced rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(identityQuery.findCustomerByExternalKey("cust-rollback")).isEmpty();
    }

    @Test
    void enforcesDatabaseAuthenticationDisabledSuspendedRejectionAndCustomerIsolationOverHttp() throws Exception {
        CustomerSummary tenantA = identityManagement.createCustomer("tenant-a", "Tenant A", SupportedCurrency.BDT);
        CustomerSummary tenantB = identityManagement.createCustomer("tenant-b", "Tenant B", SupportedCurrency.USD);

        UserSummary admin = identityManagement.createUser(
                "admin@meterflow.example",
                "Platform Admin",
                "AdminSecretPass#2026",
                PlatformRole.ADMIN
        );
        UserSummary operator = identityManagement.createUser(
                "operator@meterflow.example",
                "Billing Operator",
                "OperatorSecretPass#2026",
                PlatformRole.OPERATOR
        );
        UserSummary aliceA = identityManagement.createUser(
                "alice@tenanta.example",
                "Alice Tenant A",
                "AliceSecretPass#2026",
                PlatformRole.CUSTOMER
        );
        identityManagement.assignMembership(aliceA.id(), tenantA.id(), MembershipRole.OWNER);

        UserSummary disabledUser = identityManagement.createUser(
                "disabled@tenanta.example",
                "Disabled User",
                "DisabledSecretPass#2026",
                PlatformRole.CUSTOMER
        );
        identityManagement.updateUserStatus(disabledUser.id(), UserStatus.DISABLED);

        UserSummary suspendedUser = identityManagement.createUser(
                "suspended@tenanta.example",
                "Suspended User",
                "SuspendedSecretPass#2026",
                PlatformRole.CUSTOMER
        );
        identityManagement.updateUserStatus(suspendedUser.id(), UserStatus.SUSPENDED);

        // Unauthenticated API access rejected with 401 ProblemDetail
        mockMvc.perform(get("/api/v1/customer/" + tenantA.id() + "/summary"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:meterflow:error:authentication-failed"));

        // Disabled and suspended users rejected with 401
        mockMvc.perform(get("/api/v1/customer/" + tenantA.id() + "/summary")
                        .with(httpBasic("disabled@tenanta.example", "DisabledSecretPass#2026")))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/customer/" + tenantA.id() + "/summary")
                        .with(httpBasic("suspended@tenanta.example", "SuspendedSecretPass#2026")))
                .andExpect(status().isUnauthorized());

        // Customer A accesses own tenant (including case-insensitive email) -> 200 OK
        mockMvc.perform(get("/api/v1/customer/" + tenantA.id() + "/summary")
                        .with(httpBasic("ALICE@TENANTA.EXAMPLE", "AliceSecretPass#2026")))
                .andExpect(status().isOk());

        // Customer A attempts cross-tenant access to Tenant B -> 403 Forbidden (non-leaking)
        mockMvc.perform(get("/api/v1/customer/" + tenantB.id() + "/summary")
                        .with(httpBasic("alice@tenanta.example", "AliceSecretPass#2026")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:meterflow:error:access-denied"))
                .andExpect(jsonPath("$.detail").value("Access is denied"));

        // Customer A denied on Admin & Operator endpoints
        mockMvc.perform(get("/api/v1/admin/ping")
                        .with(httpBasic("alice@tenanta.example", "AliceSecretPass#2026")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/operator/ping")
                        .with(httpBasic("alice@tenanta.example", "AliceSecretPass#2026")))
                .andExpect(status().isForbidden());

        // Operator accesses operator + customer resources, denied on admin endpoint
        mockMvc.perform(get("/api/v1/operator/ping")
                        .with(httpBasic(operator.email(), "OperatorSecretPass#2026")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/customer/" + tenantB.id() + "/summary")
                        .with(httpBasic(operator.email(), "OperatorSecretPass#2026")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/ping")
                        .with(httpBasic(operator.email(), "OperatorSecretPass#2026")))
                .andExpect(status().isForbidden());

        // Admin accesses admin, operator, and customer endpoints
        mockMvc.perform(get("/api/v1/admin/ping")
                        .with(httpBasic(admin.email(), "AdminSecretPass#2026")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/customer/" + tenantA.id() + "/summary")
                        .with(httpBasic(admin.email(), "AdminSecretPass#2026")))
                .andExpect(status().isOk());

        // Revoking Alice's membership immediately blocks access even with valid credentials
        identityManagement.revokeMembership(aliceA.id(), tenantA.id());
        mockMvc.perform(get("/api/v1/customer/" + tenantA.id() + "/summary")
                        .with(httpBasic("alice@tenanta.example", "AliceSecretPass#2026")))
                .andExpect(status().isForbidden());
    }

    @Test
    void enforcesSessionFixationMigrationCsrfAndSecureLogoutOnPortalFormLogin() throws Exception {
        identityManagement.createUser(
                "portal-admin@meterflow.example",
                "Portal Admin",
                "PortalAdminPass#2026",
                PlatformRole.ADMIN
        );

        MockHttpSession preLoginSession = new MockHttpSession();
        String preLoginSessionId = preLoginSession.getId();

        // Missing CSRF on login fails
        mockMvc.perform(post("/login")
                        .session(preLoginSession)
                        .param("username", "portal-admin@meterflow.example")
                        .param("password", "PortalAdminPass#2026"))
                .andExpect(status().isForbidden());

        // Valid form login rotates session ID (session fixation protection)
        MvcResult loginResult = mockMvc.perform(post("/login")
                        .session(preLoginSession)
                        .with(csrf())
                        .param("username", "portal-admin@meterflow.example")
                        .param("password", "PortalAdminPass#2026"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andReturn();

        MockHttpSession authenticatedSession = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(authenticatedSession).isNotNull();
        assertThat(authenticatedSession.getId()).isNotEqualTo(preLoginSessionId);

        // Logout invalidates session
        mockMvc.perform(post("/logout").session(authenticatedSession).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));
        assertThat(authenticatedSession.isInvalid()).isTrue();
    }

    @Test
    void customerAccessGuardVerifiesDirectProgrammaticCustomerScopeAndLiveRevocation() {
        CustomerSummary tenantA = identityManagement.createCustomer("prog-a", "Prog A", SupportedCurrency.BDT);
        CustomerSummary tenantB = identityManagement.createCustomer("prog-b", "Prog B", SupportedCurrency.USD);
        UserSummary user = identityManagement.createUser(
                "prog@tenanta.example",
                "Prog Customer",
                "ProgSecretPass#2026",
                PlatformRole.CUSTOMER
        );
        identityManagement.assignMembership(user.id(), tenantA.id(), MembershipRole.MEMBER);

        UserDetails principal = userDetailsService.loadUserByUsername(user.email());
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities())
        );

        assertThat(customerAccessGuard.canAccessCustomer(tenantA.id())).isTrue();
        assertThat(customerAccessGuard.canAccessCustomer(tenantB.id())).isFalse();
        assertThatCode(() -> customerAccessGuard.requireCustomerAccess(tenantA.id())).doesNotThrowAnyException();
        assertThatThrownBy(() -> customerAccessGuard.requireCustomerAccess(tenantB.id()))
                .isInstanceOf(UnauthorizedCustomerAccessException.class);
        assertThat(customerAccessGuard.requireCustomerScope()).containsExactly(tenantA.id());

        // Suspending user mid-session immediately revokes access
        identityManagement.updateUserStatus(user.id(), UserStatus.SUSPENDED);
        assertThatThrownBy(() -> customerAccessGuard.requireCustomerAccess(tenantA.id()))
                .isInstanceOf(UnauthorizedCustomerAccessException.class);
    }
}
