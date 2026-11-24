package com.meterflow.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meterflow.identity.api.MeterFlowRoles;
import com.meterflow.identity.internal.SecurityConfiguration;
import com.meterflow.portal.internal.CorrelationIdFilter;
import com.meterflow.portal.internal.FoundationPortalController;
import com.meterflow.portal.internal.GlobalApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
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
        SecurityFoundationTests.TestProbeController.class
})
class SecurityFoundationTests {

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
    void publicPortalRoutesAreAccessibleAndAttachCorrelationId() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(header().exists(CorrelationIdFilter.CORRELATION_HEADER));

        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(header().exists(CorrelationIdFilter.CORRELATION_HEADER));
    }

    @Test
    void anonymousRequestsToProtectedRoutesAreRejectedAppropriately() throws Exception {
        mockMvc.perform(get("/admin/probe"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        mockMvc.perform(get("/customer/probe"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        mockMvc.perform(get("/v3/api-docs/probe"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        mockMvc.perform(get("/api/v1/probe"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = MeterFlowRoles.CUSTOMER)
    void customerRoleIsIsolatedFromOperatorAdminApiAndOpenApiRoutes() throws Exception {
        mockMvc.perform(get("/customer/probe")).andExpect(status().isOk());
        mockMvc.perform(get("/operator/probe")).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/probe")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/probe")).andExpect(status().isForbidden());
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
    @WithMockUser(roles = MeterFlowRoles.OPERATOR)
    void apiIllegalArgumentReturnsProblemDetailWithCorrelationId() throws Exception {
        mockMvc.perform(get("/api/v1/bad-arg").header(CorrelationIdFilter.CORRELATION_HEADER, "req-trace-123"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(CorrelationIdFilter.CORRELATION_HEADER, "req-trace-123"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail").value("Simulated invalid argument"))
                .andExpect(jsonPath("$.correlationId").value("req-trace-123"));
    }
}
