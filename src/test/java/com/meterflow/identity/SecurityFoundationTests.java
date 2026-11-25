package com.meterflow.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meterflow.identity.api.DuplicateEmailException;
import com.meterflow.identity.api.IdentityResourceNotFoundException;
import com.meterflow.identity.api.InvalidMembershipException;
import com.meterflow.identity.api.MeterFlowRoles;
import com.meterflow.identity.api.UnauthorizedCustomerAccessException;
import com.meterflow.identity.internal.SecurityConfiguration;
import com.meterflow.portal.internal.CorrelationIdFilter;
import com.meterflow.portal.internal.FoundationPortalController;
import com.meterflow.portal.internal.GlobalApiExceptionHandler;
import com.meterflow.shared.CurrencyMismatchException;
import com.meterflow.shared.InvalidCurrencyException;
import com.meterflow.shared.InvalidMoneyException;
import com.meterflow.shared.SupportedCurrency;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = {
        FoundationPortalController.class,
        SecurityFoundationTests.TestProbeController.class
})
@ActiveProfiles("test")
@Import({
        SecurityConfiguration.class,
        CorrelationIdFilter.class,
        GlobalApiExceptionHandler.class,
        SecurityFoundationTests.SliceSecurityTestConfig.class,
        SecurityFoundationTests.TestProbeController.class
})
class SecurityFoundationTests {

    @TestConfiguration(proxyBeanMethods = false)
    static class SliceSecurityTestConfig {
        @Bean
        UserDetailsService sliceUserDetailsService() {
            return username -> {
                throw new UsernameNotFoundException("Not found: " + username);
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @RestController
    static class TestProbeController {

        @GetMapping("/admin/probe")
        ResponseEntity<String> adminProbe() {
            return ResponseEntity.ok("admin-ok");
        }

        @GetMapping("/operator/probe")
        ResponseEntity<String> operatorProbe() {
            return ResponseEntity.ok("operator-ok");
        }

        @PostMapping("/operator/probe")
        ResponseEntity<String> operatorPostProbe() {
            return ResponseEntity.ok("operator-post-ok");
        }

        @GetMapping("/customer/probe")
        ResponseEntity<String> customerProbe() {
            return ResponseEntity.ok("customer-ok");
        }

        @GetMapping("/api/v1/probe")
        ResponseEntity<String> apiProbe() {
            return ResponseEntity.ok("api-ok");
        }

        @GetMapping("/api/v1/bad-arg")
        ResponseEntity<String> apiBadArgumentProbe() {
            throw new IllegalArgumentException("Simulated invalid argument");
        }

        @GetMapping("/api/v1/errors/currency")
        ResponseEntity<String> invalidCurrencyProbe() {
            throw new InvalidCurrencyException("Unsupported currency code 'EUR'");
        }

        @GetMapping("/api/v1/errors/currency-mismatch")
        ResponseEntity<String> currencyMismatchProbe() {
            throw new CurrencyMismatchException(SupportedCurrency.BDT, SupportedCurrency.USD);
        }

        @GetMapping("/api/v1/errors/money")
        ResponseEntity<String> invalidMoneyProbe() {
            throw new InvalidMoneyException("Division by zero is not allowed");
        }

        @GetMapping("/api/v1/errors/duplicate-email")
        ResponseEntity<String> duplicateEmailProbe() {
            throw new DuplicateEmailException("duplicate");
        }

        @GetMapping("/api/v1/errors/invalid-membership")
        ResponseEntity<String> invalidMembershipProbe() {
            throw new InvalidMembershipException("Customer memberships require CUSTOMER role");
        }

        @GetMapping("/api/v1/errors/not-found")
        ResponseEntity<String> notFoundProbe() {
            throw new IdentityResourceNotFoundException("Customer not found");
        }

        @GetMapping("/api/v1/errors/unauthorized-customer")
        ResponseEntity<String> unauthorizedCustomerProbe() {
            throw new UnauthorizedCustomerAccessException("Access to tenant B denied");
        }

        @GetMapping("/v3/api-docs/probe")
        ResponseEntity<String> openApiProbe() {
            return ResponseEntity.ok("openapi-ok");
        }
    }

    @Test
    void bcryptPasswordEncoderHashesPasswordsSecurely() {
        String encoded = passwordEncoder.encode("secret-foundation-pass");
        assertThat(encoded).isNotEqualTo("secret-foundation-pass");
        assertThat(encoded).startsWith("$2a$10$");
        assertThat(passwordEncoder.matches("secret-foundation-pass", encoded)).isTrue();
    }

    @Test
    void publicPortalRoutesAreAccessibleAttachCorrelationIdAndDefensiveHeaders() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(header().exists(CorrelationIdFilter.CORRELATION_HEADER))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'"));

        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(header().exists(CorrelationIdFilter.CORRELATION_HEADER));
    }

    @Test
    void anonymousRequestsToProtectedRoutesAreRejectedWithStandardizedErrors() throws Exception {
        mockMvc.perform(get("/admin/probe"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        mockMvc.perform(get("/customer/probe"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        mockMvc.perform(get("/v3/api-docs/probe"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        mockMvc.perform(get("/api/v1/probe").header(CorrelationIdFilter.CORRELATION_HEADER, "anon-trace-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:meterflow:error:authentication-failed"))
                .andExpect(jsonPath("$.title").value("Authentication Required"))
                .andExpect(jsonPath("$.correlationId").value("anon-trace-1"));
    }

    @Test
    @WithMockUser(roles = MeterFlowRoles.CUSTOMER)
    void customerRoleIsIsolatedFromOperatorAdminApiAndOpenApiRoutes() throws Exception {
        mockMvc.perform(get("/customer/probe")).andExpect(status().isOk());
        mockMvc.perform(get("/operator/probe")).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/probe")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/probe"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:meterflow:error:access-denied"));
        mockMvc.perform(get("/v3/api-docs/probe")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = MeterFlowRoles.OPERATOR)
    void operatorRoleAccessesOperatorApiAndOpenApiAndRequiresCsrfOnPortalPosts() throws Exception {
        mockMvc.perform(get("/operator/probe")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/probe")).andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs/probe")).andExpect(status().isOk());
        mockMvc.perform(get("/admin/probe")).andExpect(status().isForbidden());

        mockMvc.perform(post("/operator/probe")).andExpect(status().isForbidden());
        mockMvc.perform(post("/operator/probe").with(csrf())).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = MeterFlowRoles.ADMIN)
    void adminRoleAccessesAdminOperatorApiAndOpenApiRoutes() throws Exception {
        mockMvc.perform(get("/admin/probe")).andExpect(status().isOk());
        mockMvc.perform(get("/operator/probe")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/probe")).andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs/probe")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = MeterFlowRoles.CUSTOMER)
    void logoutRequiresCsrfDeletesSessionCookieAndRedirectsToLogin() throws Exception {
        mockMvc.perform(post("/logout")).andExpect(status().isForbidden());

        mockMvc.perform(post("/logout").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"))
                .andExpect(cookie().maxAge("JSESSIONID", 0));
    }

    @Test
    @WithMockUser(roles = MeterFlowRoles.OPERATOR)
    void globalExceptionHandlerMapsFinancialAndIdentityErrorsToRfc9457ProblemDetails() throws Exception {
        mockMvc.perform(get("/api/v1/bad-arg").header(CorrelationIdFilter.CORRELATION_HEADER, "req-trace-123"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(CorrelationIdFilter.CORRELATION_HEADER, "req-trace-123"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail").value("Simulated invalid argument"))
                .andExpect(jsonPath("$.correlationId").value("req-trace-123"));

        mockMvc.perform(get("/api/v1/errors/currency"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:meterflow:error:invalid-currency"));

        mockMvc.perform(get("/api/v1/errors/currency-mismatch"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:meterflow:error:currency-mismatch"));

        mockMvc.perform(get("/api/v1/errors/money"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:meterflow:error:invalid-money"));

        mockMvc.perform(get("/api/v1/errors/duplicate-email"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:meterflow:error:duplicate-email"));

        mockMvc.perform(get("/api/v1/errors/invalid-membership"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:meterflow:error:invalid-membership"));

        mockMvc.perform(get("/api/v1/errors/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:meterflow:error:identity-not-found"));

        mockMvc.perform(get("/api/v1/errors/unauthorized-customer"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:meterflow:error:access-denied"))
                .andExpect(jsonPath("$.detail").value("Access is denied"));
    }
}
