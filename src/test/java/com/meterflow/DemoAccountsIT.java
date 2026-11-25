package com.meterflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meterflow.identity.api.CustomerSummary;
import com.meterflow.identity.api.DemoAccountCatalog;
import com.meterflow.identity.api.IdentityManagementFacade;
import com.meterflow.identity.api.IdentityQueryFacade;
import com.meterflow.identity.api.PlatformRole;
import com.meterflow.identity.api.UserSummary;
import com.meterflow.shared.SupportedCurrency;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * End-to-end PostgreSQL 17 Testcontainers verification for the opt-in Demo Accounts system:
 * idempotent provisioning, real BCrypt form authentication, strict RBAC & tenant isolation,
 * CSP-compliant sign-in UI rendering, and compatibility with Flyway V1-V8 without new migrations.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "meterflow.demo.enabled=true",
        "meterflow.demo.seed-sample-data=true",
        "meterflow.demo.admin.email=demo-admin@meterflow.demo",
        "meterflow.demo.admin.password=DemoAdminPass#2026",
        "meterflow.demo.operator.email=demo-operator@meterflow.demo",
        "meterflow.demo.operator.password=DemoOperPass#2026",
        "meterflow.demo.customer.email=demo-customer@meterflow.demo",
        "meterflow.demo.customer.password=DemoCustPass#2026"
})
@Import(DemoAccountsIT.PostgresContainerConfiguration.class)
class DemoAccountsIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresContainerConfiguration {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer("postgres:17-alpine");
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private DemoAccountCatalog demoAccountCatalog;
    @Autowired private IdentityQueryFacade identityQueryFacade;
    @Autowired private IdentityManagementFacade identityManagementFacade;
    @Autowired private List<ApplicationRunner> applicationRunners;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void demoAccountsProvisionIdempotentlyRenderOnLoginAuthenticateThroughBcryptAndEnforceRbac() throws Exception {
        // 1. Verify DemoAccountCatalog & DB state after initial startup
        assertThat(demoAccountCatalog.isDemoModeEnabled()).isTrue();
        assertThat(demoAccountCatalog.getDemoAccounts()).hasSize(3);

        UserSummary admin = identityQueryFacade.findUserByEmail("demo-admin@meterflow.demo").orElseThrow();
        UserSummary operator = identityQueryFacade.findUserByEmail("demo-operator@meterflow.demo").orElseThrow();
        UserSummary customer = identityQueryFacade.findUserByEmail("demo-customer@meterflow.demo").orElseThrow();
        CustomerSummary demoTenant = identityQueryFacade.findCustomerByExternalKey("demo-acme-cloud").orElseThrow();

        assertThat(admin.role()).isEqualTo(PlatformRole.ADMIN);
        assertThat(operator.role()).isEqualTo(PlatformRole.OPERATOR);
        assertThat(customer.role()).isEqualTo(PlatformRole.CUSTOMER);
        assertThat(identityQueryFacade.findAuthorizedCustomerIds(customer.id())).containsExactly(demoTenant.id());

        String originalCustomerHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE email = ?", String.class, "demo-customer@meterflow.demo"
        );
        assertThat(originalCustomerHash).startsWith("$2a$10$");

        // 2. Re-run all ApplicationRunners to prove strict restart idempotency (0 duplicates, 0 password overwrites)
        long usersBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        long customersBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM customers", Long.class);
        long subsBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM subscriptions", Long.class);
        long invoicesBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoices", Long.class);

        for (ApplicationRunner runner : applicationRunners) {
            runner.run(new DefaultApplicationArguments());
        }

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long.class)).isEqualTo(usersBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM customers", Long.class)).isEqualTo(customersBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM subscriptions", Long.class)).isEqualTo(subsBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoices", Long.class)).isEqualTo(invoicesBefore);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE email = ?", String.class, "demo-customer@meterflow.demo"
        )).isEqualTo(originalCustomerHash);
        assertThat(subsBefore).isGreaterThanOrEqualTo(1L);
        assertThat(invoicesBefore).isGreaterThanOrEqualTo(1L);

        // 3. Sign-in page & public JS asset render cleanly under strict CSP
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("University Demonstration Mode Enabled")));

        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'"))
                .andExpect(content().string(containsString("Demo Accounts")))
                .andExpect(content().string(containsString("Demo-Only Environment")))
                .andExpect(content().string(containsString("demo-admin@meterflow.demo")))
                .andExpect(content().string(containsString("DemoAdminPass#2026")))
                .andExpect(content().string(containsString("demo-operator@meterflow.demo")))
                .andExpect(content().string(containsString("DemoOperPass#2026")))
                .andExpect(content().string(containsString("demo-customer@meterflow.demo")))
                .andExpect(content().string(containsString("DemoCustPass#2026")))
                .andExpect(content().string(containsString("Copy Credentials")))
                .andExpect(content().string(containsString("Use This Account")))
                .andExpect(content().string(containsString("/js/demo-login.js")));

        mockMvc.perform(get("/js/demo-login.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-copy-value")))
                .andExpect(content().string(containsString("data-fill-email")));

        // 4. Reject invalid password on real form login (authentication is never bypassed)
        mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "demo-admin@meterflow.demo")
                        .param("password", "WrongPassword#2026"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));

        // 5. Real BCrypt authentication & authorization verification for Demo Admin
        MockHttpSession adminSession = loginAs("demo-admin@meterflow.demo", "DemoAdminPass#2026");
        mockMvc.perform(get("/admin/overview").session(adminSession))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/operator/overview"));
        mockMvc.perform(get("/operator/overview").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("MeterFlow Operator Console")));
        mockMvc.perform(get("/actuator/metrics").session(adminSession))
                .andExpect(status().isOk());
        mockMvc.perform(get("/customer/dashboard").session(adminSession))
                .andExpect(status().isForbidden());

        // 6. Real BCrypt authentication & authorization verification for Demo Operator
        MockHttpSession operatorSession = loginAs("demo-operator@meterflow.demo", "DemoOperPass#2026");
        mockMvc.perform(get("/operator/overview").session(operatorSession)).andExpect(status().isOk());
        mockMvc.perform(get("/operator/customers").session(operatorSession)).andExpect(status().isOk());
        mockMvc.perform(get("/operator/subscriptions").session(operatorSession)).andExpect(status().isOk());
        mockMvc.perform(get("/operator/billing").session(operatorSession)).andExpect(status().isOk());
        mockMvc.perform(get("/operator/payments").session(operatorSession)).andExpect(status().isOk());
        mockMvc.perform(get("/operator/webhooks").session(operatorSession)).andExpect(status().isOk());
        mockMvc.perform(get("/operator/catalog").session(operatorSession)).andExpect(status().isOk());
        mockMvc.perform(get("/operator/simulation").session(operatorSession)).andExpect(status().isOk());
        mockMvc.perform(get("/admin/overview").session(operatorSession))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/metrics").session(operatorSession))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/customer/dashboard").session(operatorSession))
                .andExpect(status().isForbidden());

        // 7. Real BCrypt authentication, synthetic demo workspace & strict tenant isolation for Demo Customer
        CustomerSummary separateRealCustomer = identityManagementFacade.createCustomer(
                "real-other-tenant", "Private Enterprise Corp", SupportedCurrency.USD
        );

        MockHttpSession customerSession = loginAs("demo-customer@meterflow.demo", "DemoCustPass#2026");
        mockMvc.perform(get("/customer/dashboard").session(customerSession))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Acme Cloud Demo")));
        mockMvc.perform(get("/customer/usage").session(customerSession)).andExpect(status().isOk());
        mockMvc.perform(get("/customer/subscription").session(customerSession)).andExpect(status().isOk());
        mockMvc.perform(get("/customer/invoices").session(customerSession))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("INV-")));
        mockMvc.perform(get("/customer/payments").session(customerSession)).andExpect(status().isOk());
        mockMvc.perform(get("/customer/credits").session(customerSession))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Synthetic Demo Welcome Credit")));
        mockMvc.perform(get("/customer/notifications").session(customerSession)).andExpect(status().isOk());
        mockMvc.perform(get("/customer/webhooks").session(customerSession)).andExpect(status().isOk());
        mockMvc.perform(get("/customer/simulation").session(customerSession)).andExpect(status().isOk());
        mockMvc.perform(get("/operator/overview").session(customerSession))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/overview").session(customerSession))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/customer/dashboard")
                        .param("customerId", separateRealCustomer.id().toString())
                        .session(customerSession))
                .andExpect(status().isForbidden());

        // 8. Existing standard user provisioning & login remains unaffected
        identityManagementFacade.createUser(
                "regular-op@meterflow.test", "Regular Operator", "RegularOpPass#2026", PlatformRole.OPERATOR
        );
        MockHttpSession regularSession = loginAs("regular-op@meterflow.test", "RegularOpPass#2026");
        mockMvc.perform(get("/operator/overview").session(regularSession))
                .andExpect(status().isOk());
    }

    private MockHttpSession loginAs(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", email)
                        .param("password", password))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andReturn();
        HttpSession session = result.getRequest().getSession(false);
        assertThat(session).isInstanceOf(MockHttpSession.class);
        return (MockHttpSession) session;
    }
}
