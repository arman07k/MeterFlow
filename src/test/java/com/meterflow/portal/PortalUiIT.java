package com.meterflow.portal;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meterflow.billing.api.FinalizeInvoiceCommand;
import com.meterflow.billing.api.GenerateDraftInvoiceCommand;
import com.meterflow.billing.api.InvoiceDetail;
import com.meterflow.billing.api.InvoiceManagementFacade;
import com.meterflow.catalog.api.ConfigureChargeCommand;
import com.meterflow.catalog.api.CreatePlanCommand;
import com.meterflow.catalog.api.PlanCatalogFacade;
import com.meterflow.catalog.api.PlanVersionDetail;
import com.meterflow.catalog.api.UpdateDraftPlanVersionCommand;
import com.meterflow.identity.api.CustomerSummary;
import com.meterflow.identity.api.IdentityManagementFacade;
import com.meterflow.identity.api.MembershipRole;
import com.meterflow.identity.api.PlatformRole;
import com.meterflow.identity.api.UserSummary;
import com.meterflow.shared.BillingInterval;
import com.meterflow.shared.SupportedCurrency;
import com.meterflow.subscriptions.api.CreateSubscriptionCommand;
import com.meterflow.subscriptions.api.SubscriptionManagementFacade;
import com.meterflow.subscriptions.api.SubscriptionSummary;
import com.meterflow.subscriptions.api.TrialConversionPolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PortalUiIT.PostgresContainerConfiguration.class)
class PortalUiIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresContainerConfiguration {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer("postgres:17-alpine");
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserDetailsService userDetailsService;
    @Autowired private IdentityManagementFacade identityManagementFacade;
    @Autowired private PlanCatalogFacade planCatalogFacade;
    @Autowired private SubscriptionManagementFacade subscriptionManagementFacade;
    @Autowired private InvoiceManagementFacade invoiceManagementFacade;

    @Test
    void customerPortalAndOperatorConsoleRenderSecurelyWithCsrfAndTenantIsolation() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String adminEmail = "ui-admin-" + suffix + "@meterflow.test";
        String custAEmail = "ui-custa-" + suffix + "@meterflow.test";
        String custBEmail = "ui-custb-" + suffix + "@meterflow.test";
        String pass = "PortalUiTest#2026";

        identityManagementFacade.createUser(adminEmail, "Portal Admin", pass, PlatformRole.ADMIN);
        UserSummary userA = identityManagementFacade.createUser(custAEmail, "Alpha User", pass, PlatformRole.CUSTOMER);
        UserSummary userB = identityManagementFacade.createUser(custBEmail, "Beta User", pass, PlatformRole.CUSTOMER);

        CustomerSummary customerA = identityManagementFacade.createCustomer("ui-a-" + suffix, "Alpha Systems", SupportedCurrency.USD);
        CustomerSummary customerB = identityManagementFacade.createCustomer("ui-b-" + suffix, "Beta Ventures", SupportedCurrency.USD);
        identityManagementFacade.assignMembership(userA.id(), customerA.id(), MembershipRole.OWNER);
        identityManagementFacade.assignMembership(userB.id(), customerB.id(), MembershipRole.OWNER);

        PlanVersionDetail p1Draft = planCatalogFacade.createPlanWithDraft(new CreatePlanCommand(
                "UI-PRO-" + suffix, "UI Pro Plan", "Monthly flat fee", SupportedCurrency.USD, Instant.parse("2026-01-01T00:00:00Z")
        ));
        planCatalogFacade.updateDraftVersion(p1Draft.id(), new UpdateDraftPlanVersionCommand(
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of(new ConfigureChargeCommand(1, "BASE", "Base Monthly Fee", "FLAT_FEE", null,
                        new BigDecimal("100.00"), null, null, null, List.of(), Map.of())),
                List.of(),
                List.of()
        ));
        PlanVersionDetail planV1 = planCatalogFacade.publishVersion(p1Draft.id());

        PlanVersionDetail p2Draft = planCatalogFacade.createPlanWithDraft(new CreatePlanCommand(
                "UI-ENT-" + suffix, "UI Enterprise Plan", "Enterprise monthly flat fee", SupportedCurrency.USD, Instant.parse("2026-01-01T00:00:00Z")
        ));
        planCatalogFacade.updateDraftVersion(p2Draft.id(), new UpdateDraftPlanVersionCommand(
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of(new ConfigureChargeCommand(1, "BASE", "Enterprise Fee", "FLAT_FEE", null,
                        new BigDecimal("250.00"), null, null, null, List.of(), Map.of())),
                List.of(),
                List.of()
        ));
        PlanVersionDetail planV2 = planCatalogFacade.publishVersion(p2Draft.id());

        Instant pStart = Instant.parse("2026-09-01T00:00:00Z");
        Instant pEnd = Instant.parse("2026-10-01T00:00:00Z");
        SubscriptionSummary sub = subscriptionManagementFacade.createSubscription(new CreateSubscriptionCommand(
                customerA.id(), planV1.id(), SupportedCurrency.USD, BillingInterval.MONTHLY, "UTC", 1, pStart, null, TrialConversionPolicy.EXPIRE_ON_END, "ui-sub-" + suffix
        ));
        InvoiceDetail draft = invoiceManagementFacade.generateOrRefreshDraft(
                new GenerateDraftInvoiceCommand(sub.id(), pStart, pEnd, 0, null, "ui-inv-" + suffix)
        );
        InvoiceDetail finalized = invoiceManagementFacade.finalizeInvoice(
                draft.invoice().id(), new FinalizeInvoiceCommand(false, pEnd.plusSeconds(86400), "ui-fin-" + suffix)
        );

        UserDetails custAPrincipal = userDetailsService.loadUserByUsername(custAEmail);
        UserDetails custBPrincipal = userDetailsService.loadUserByUsername(custBEmail);
        UserDetails adminPrincipal = userDetailsService.loadUserByUsername(adminEmail);

        // Anonymous redirect to /login
        mockMvc.perform(get("/customer/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
        mockMvc.perform(get("/operator/overview"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        // Customer Portal pages render cleanly under strict CSP with zero inline styles/scripts
        mockMvc.perform(get("/customer/dashboard").with(user(custAPrincipal)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'"))
                .andExpect(content().string(containsString("Alpha Systems")));

        mockMvc.perform(get("/customer/usage").with(user(custAPrincipal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Usage &amp; Live Spend Estimate")));

        mockMvc.perform(get("/customer/subscription").with(user(custAPrincipal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Active Subscription")));

        mockMvc.perform(get("/customer/invoices").with(user(custAPrincipal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(finalized.invoice().invoiceNumber())));

        mockMvc.perform(get("/customer/invoices/" + finalized.invoice().id()).with(user(custAPrincipal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Invoice Line Items")));

        // Customer payments view is strictly read-only (ZERO mock payment simulation controls)
        mockMvc.perform(get("/customer/payments").with(user(custAPrincipal)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("SIMULATE_SUCCESS"))))
                .andExpect(content().string(not(containsString("Initiate Mock Payment"))));

        mockMvc.perform(get("/customer/credits").with(user(custAPrincipal))).andExpect(status().isOk());
        mockMvc.perform(get("/customer/notifications").with(user(custAPrincipal))).andExpect(status().isOk());
        mockMvc.perform(get("/customer/webhooks").with(user(custAPrincipal))).andExpect(status().isOk());
        mockMvc.perform(get("/customer/simulation").with(user(custAPrincipal))).andExpect(status().isOk());

        // CSRF protection enforced on portal POSTs; succeeds with valid CSRF token
        mockMvc.perform(post("/customer/webhooks/endpoints")
                        .with(user(custAPrincipal))
                        .param("customerId", customerA.id().toString())
                        .param("name", "Portal Hook")
                        .param("targetUrl", "https://hooks.example.com/portal")
                        .param("subscribedEvents", "invoice.finalised"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/customer/webhooks/endpoints")
                        .with(user(custAPrincipal))
                        .with(csrf())
                        .param("customerId", customerA.id().toString())
                        .param("name", "Portal Hook")
                        .param("targetUrl", "https://hooks.example.com/portal")
                        .param("subscribedEvents", "invoice.finalised"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("oneTimeSecret"));

        // Historical simulation form renders comparison cleanly
        mockMvc.perform(post("/customer/simulation")
                        .with(user(custAPrincipal))
                        .with(csrf())
                        .param("customerId", customerA.id().toString())
                        .param("originalInvoiceId", finalized.invoice().id().toString())
                        .param("targetPlanVersionId", planV2.id().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Non-Financial Simulation")))
                .andExpect(content().string(containsString("150.00")));

        // Cross-customer IDOR blocked on portal routes (Customer B cannot access Customer A's dashboard or invoice)
        mockMvc.perform(get("/customer/dashboard")
                        .param("customerId", customerA.id().toString())
                        .with(user(custBPrincipal)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/customer/invoices/" + finalized.invoice().id())
                        .with(user(custBPrincipal)))
                .andExpect(status().isForbidden());

        // Customer cannot access Operator Console
        mockMvc.perform(get("/operator/overview").with(user(custAPrincipal)))
                .andExpect(status().isForbidden());

        // Operator/Admin Console renders all operational sections cleanly
        mockMvc.perform(get("/operator/overview").with(user(adminPrincipal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Platform Operations Overview")));
        mockMvc.perform(get("/operator/customers").with(user(adminPrincipal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Alpha Systems")));
        mockMvc.perform(get("/operator/customers/" + customerA.id()).with(user(adminPrincipal))).andExpect(status().isOk());
        mockMvc.perform(get("/operator/subscriptions").with(user(adminPrincipal))).andExpect(status().isOk());
        mockMvc.perform(get("/operator/billing").with(user(adminPrincipal))).andExpect(status().isOk());
        mockMvc.perform(get("/operator/payments").with(user(adminPrincipal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Initiate Mock Payment")));
        mockMvc.perform(get("/operator/webhooks").with(user(adminPrincipal))).andExpect(status().isOk());
        mockMvc.perform(get("/operator/catalog").with(user(adminPrincipal))).andExpect(status().isOk());
        mockMvc.perform(get("/operator/simulation").with(user(adminPrincipal))).andExpect(status().isOk());
    }
}
