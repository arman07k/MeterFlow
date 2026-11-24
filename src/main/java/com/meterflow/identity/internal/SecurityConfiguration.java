package com.meterflow.identity.internal;

import com.meterflow.identity.api.MeterFlowRoles;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Dual Spring Security 7 filter chains per ADR-006:
 * - Order 1: Stateless /api/v1/** surface for automated clients.
 * - Order 2: Stateful Thymeleaf portal surface with BCrypt, RBAC, and CSRF enforcement.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfiguration {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/api/v1/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/admin/**").hasRole(MeterFlowRoles.ADMIN)
                        .anyRequest().hasAnyRole(MeterFlowRoles.ADMIN, MeterFlowRoles.OPERATOR)
                )
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain portalSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/login", "/actuator/health", "/actuator/health/**", "/actuator/info", "/css/**", "/error").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").hasAnyRole(MeterFlowRoles.ADMIN, MeterFlowRoles.OPERATOR)
                        .requestMatchers("/actuator/**").hasRole(MeterFlowRoles.ADMIN)
                        .requestMatchers("/admin/**").hasRole(MeterFlowRoles.ADMIN)
                        .requestMatchers("/operator/**").hasAnyRole(MeterFlowRoles.ADMIN, MeterFlowRoles.OPERATOR)
                        .requestMatchers("/customer/**").hasRole(MeterFlowRoles.CUSTOMER)
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/", false)
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutSuccessUrl("/login?logout")
                        .permitAll()
                )
                .build();
    }

    /**
     * Local/test bootstrap accounts active only when meterflow.security.bootstrap.enabled=true.
     * Replaced by database-backed identity persistence in the dedicated identity module task.
     */
    @Bean
    @ConditionalOnProperty(name = "meterflow.security.bootstrap.enabled", havingValue = "true")
    public UserDetailsService bootstrapUserDetailsService(
            PasswordEncoder passwordEncoder,
            @Value("${meterflow.security.bootstrap.admin-password}") String adminPassword,
            @Value("${meterflow.security.bootstrap.operator-password}") String operatorPassword,
            @Value("${meterflow.security.bootstrap.customer-password}") String customerPassword
    ) {
        UserDetails admin = User.builder()
                .username("admin@meterflow.local")
                .password(passwordEncoder.encode(adminPassword))
                .roles(MeterFlowRoles.ADMIN)
                .build();

        UserDetails operator = User.builder()
                .username("operator@meterflow.local")
                .password(passwordEncoder.encode(operatorPassword))
                .roles(MeterFlowRoles.OPERATOR)
                .build();

        UserDetails customer = User.builder()
                .username("customer@meterflow.local")
                .password(passwordEncoder.encode(customerPassword))
                .roles(MeterFlowRoles.CUSTOMER)
                .build();

        return new InMemoryUserDetailsManager(admin, operator, customer);
    }
}
