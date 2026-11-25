package com.meterflow.identity.internal;

import com.meterflow.identity.api.MeterFlowRoles;
import com.meterflow.shared.CorrelationIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dual Spring Security 7 filter chains per ADR-006:
 * - Order 1: Stateless /api/v1/** surface with RFC 9457 ProblemDetail error responses.
 * - Order 2: Stateful Thymeleaf portal surface with BCrypt, RBAC, session fixation protection,
 *   CSRF enforcement, secure logout, and defensive HTTP security headers.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfiguration {

    private static final String CSP_POLICY = "default-src 'self'; frame-ancestors 'none'";

    @Bean
    public PasswordEncoder passwordEncoder(
            @Value("${meterflow.security.bcrypt-strength:12}") int bcryptStrength
    ) {
        if (bcryptStrength < 10 || bcryptStrength > 15) {
            throw new IllegalArgumentException("BCrypt strength must be between 10 and 15");
        }
        return new BCryptPasswordEncoder(bcryptStrength);
    }

    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, JsonMapper jsonMapper) throws Exception {
        return http
                .securityMatcher("/api/v1/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> configureSecurityHeaders(headers))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/admin/**").hasRole(MeterFlowRoles.ADMIN)
                        .requestMatchers("/api/v1/customer/**").hasAnyRole(
                                MeterFlowRoles.ADMIN,
                                MeterFlowRoles.OPERATOR,
                                MeterFlowRoles.CUSTOMER
                        )
                        .anyRequest().hasAnyRole(MeterFlowRoles.ADMIN, MeterFlowRoles.OPERATOR)
                )
                .httpBasic(basic -> basic.authenticationEntryPoint((request, response, authException) -> {
                    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"MeterFlow API\"");
                    writeProblemDetail(
                            response,
                            jsonMapper,
                            HttpStatus.UNAUTHORIZED,
                            "Authentication Required",
                            "Authentication is required or credentials are invalid",
                            "urn:meterflow:error:authentication-failed",
                            request.getRequestURI()
                    );
                }))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"MeterFlow API\"");
                            writeProblemDetail(
                                    response,
                                    jsonMapper,
                                    HttpStatus.UNAUTHORIZED,
                                    "Authentication Required",
                                    "Authentication is required or credentials are invalid",
                                    "urn:meterflow:error:authentication-failed",
                                    request.getRequestURI()
                            );
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> writeProblemDetail(
                                response,
                                jsonMapper,
                                HttpStatus.FORBIDDEN,
                                "Forbidden",
                                "Access is denied",
                                "urn:meterflow:error:access-denied",
                                request.getRequestURI()
                        ))
                )
                .build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain portalSecurityFilterChain(HttpSecurity http) throws Exception {
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        AccessDeniedHandlerImpl defaultAccessDeniedHandler = new AccessDeniedHandlerImpl();

        return http
                .headers(headers -> configureSecurityHeaders(headers))
                .requestCache(cache -> cache.requestCache(requestCache))
                .sessionManagement(session -> session
                        .sessionFixation(fixation -> fixation.migrateSession())
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/login", "/actuator/health", "/actuator/health/**", "/actuator/info", "/css/**", "/js/**", "/favicon.ico", "/error").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").hasAnyRole(MeterFlowRoles.ADMIN, MeterFlowRoles.OPERATOR)
                        .requestMatchers("/actuator/**").hasRole(MeterFlowRoles.ADMIN)
                        .requestMatchers("/admin/**").hasRole(MeterFlowRoles.ADMIN)
                        .requestMatchers("/operator/**").hasAnyRole(MeterFlowRoles.ADMIN, MeterFlowRoles.OPERATOR)
                        .requestMatchers("/customer/**").hasRole(MeterFlowRoles.CUSTOMER)
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .successHandler(roleAwareLoginSuccessHandler(requestCache))
                        .permitAll()
                )
                .exceptionHandling(exceptions -> exceptions
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            // Keep full CSRF protection: only redirect back to /login?expired when a browser submits
                            // a stale _csrf token against an expired/restarted session (MissingCsrfTokenException).
                            // Missing _csrf or invalid tokens against an active session still return HTTP 403.
                            String requestPath = request.getRequestURI().substring(request.getContextPath().length());
                            if (accessDeniedException instanceof MissingCsrfTokenException
                                    && "/login".equals(requestPath)
                                    && "POST".equalsIgnoreCase(request.getMethod())) {
                                String submittedToken = request.getParameter("_csrf");
                                if (submittedToken != null && !submittedToken.isBlank()) {
                                    response.sendRedirect("/login?expired");
                                    return;
                                }
                            }
                            defaultAccessDeniedHandler.handle(request, response, accessDeniedException);
                        })
                )
                .logout(logout -> logout
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessUrl("/login?logout")
                        .permitAll()
                )
                .build();
    }

    private static AuthenticationSuccessHandler roleAwareLoginSuccessHandler(RequestCache requestCache) {
        SavedRequestAwareAuthenticationSuccessHandler delegate = new SavedRequestAwareAuthenticationSuccessHandler();
        delegate.setDefaultTargetUrl("/");
        delegate.setAlwaysUseDefaultTargetUrl(false);
        delegate.setRequestCache(requestCache);

        return (request, response, authentication) -> {
            SavedRequest savedRequest = requestCache.getRequest(request, response);
            if (savedRequest != null && !isSavedRequestCompatibleWithRole(savedRequest.getRedirectUrl(), authentication)) {
                requestCache.removeRequest(request, response);
            }
            delegate.onAuthenticationSuccess(request, response, authentication);
        };
    }

    private static boolean isSavedRequestCompatibleWithRole(String redirectUrl, Authentication authentication) {
        if (redirectUrl == null || redirectUrl.isBlank()) {
            return false;
        }
        String path;
        try {
            path = URI.create(redirectUrl).getPath();
        } catch (IllegalArgumentException ex) {
            return false;
        }
        if (path == null || path.isBlank() || "/".equals(path)) {
            return true;
        }
        boolean isAdmin = hasAuthority(authentication, MeterFlowRoles.ROLE_ADMIN);
        boolean isOperator = hasAuthority(authentication, MeterFlowRoles.ROLE_OPERATOR);
        boolean isCustomer = hasAuthority(authentication, MeterFlowRoles.ROLE_CUSTOMER);

        if (path.startsWith("/customer/") || "/customer".equals(path)) {
            return isCustomer;
        }
        if (path.startsWith("/admin/") || "/admin".equals(path) || path.startsWith("/actuator/")) {
            return isAdmin;
        }
        if (path.startsWith("/operator/") || "/operator".equals(path)
                || path.startsWith("/v3/api-docs") || path.startsWith("/swagger-ui")) {
            return isAdmin || isOperator;
        }
        return !"/favicon.ico".equals(path);
    }

    private static boolean hasAuthority(Authentication authentication, String authority) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority()));
    }

    private static void configureSecurityHeaders(
            org.springframework.security.config.annotation.web.configurers.HeadersConfigurer<HttpSecurity> headers
    ) {
        headers.contentTypeOptions(Customizer.withDefaults())
                .frameOptions(frame -> frame.deny())
                .referrerPolicy(referrer -> referrer
                        .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .contentSecurityPolicy(csp -> csp.policyDirectives(CSP_POLICY));
    }

    private static void writeProblemDetail(
            HttpServletResponse response,
            JsonMapper jsonMapper,
            HttpStatus status,
            String title,
            String detailMessage,
            String typeUri,
            String instancePath
    ) throws IOException {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, detailMessage);
        detail.setTitle(title);
        detail.setType(URI.create(typeUri));
        if (instancePath != null && !instancePath.isBlank()) {
            detail.setInstance(URI.create(instancePath));
        }
        String correlationId = MDC.get(CorrelationIds.MDC_KEY);
        if (correlationId != null && !correlationId.isBlank()) {
            detail.setProperty(CorrelationIds.MDC_KEY, correlationId);
        }
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getWriter(), detail);
    }
}
