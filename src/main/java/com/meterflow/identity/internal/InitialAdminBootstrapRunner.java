package com.meterflow.identity.internal;

import com.meterflow.identity.api.IdentityManagementFacade;
import com.meterflow.identity.api.PlatformRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explicit, environment-driven, one-time initial administrator bootstrap runner (C6, QA4).
 *
 * <p>Inactive by default ({@code meterflow.security.bootstrap-admin.enabled=false}). When enabled,
 * creates a single initial ADMIN account only if no ADMIN exists yet, fails startup safely if
 * credentials are missing or weak, never replaces existing passwords, and never logs passwords.
 */
@Component
@ConditionalOnProperty(name = "meterflow.security.bootstrap-admin.enabled", havingValue = "true")
class InitialAdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InitialAdminBootstrapRunner.class);

    private final UserRepository userRepository;
    private final IdentityManagementFacade identityManagementFacade;
    private final String bootstrapEmail;
    private final String bootstrapFullName;
    private final String bootstrapPassword;

    InitialAdminBootstrapRunner(
            UserRepository userRepository,
            IdentityManagementFacade identityManagementFacade,
            @Value("${meterflow.security.bootstrap-admin.email:}") String bootstrapEmail,
            @Value("${meterflow.security.bootstrap-admin.full-name:Platform Administrator}") String bootstrapFullName,
            @Value("${meterflow.security.bootstrap-admin.password:}") String bootstrapPassword
    ) {
        this.userRepository = userRepository;
        this.identityManagementFacade = identityManagementFacade;
        this.bootstrapEmail = bootstrapEmail;
        this.bootstrapFullName = bootstrapFullName;
        this.bootstrapPassword = bootstrapPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.existsByPlatformRole(PlatformRole.ADMIN)) {
            log.info("Initial administrator bootstrap skipped because an ADMIN account already exists");
            return;
        }

        String normalizedEmail;
        try {
            normalizedEmail = IdentityService.normalizeAndValidateEmail(bootstrapEmail);
            IdentityService.validateRawPassword(bootstrapPassword);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "Invalid meterflow.security.bootstrap-admin configuration: provide a valid email and a password of at least "
                            + IdentityService.MIN_PASSWORD_LENGTH + " characters"
            );
        }

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new IllegalStateException(
                    "Cannot bootstrap initial ADMIN: configured email already belongs to another account"
            );
        }

        String resolvedName = (bootstrapFullName == null || bootstrapFullName.isBlank())
                ? "Platform Administrator"
                : bootstrapFullName.trim();

        identityManagementFacade.createUser(normalizedEmail, resolvedName, bootstrapPassword, PlatformRole.ADMIN);
        log.info("One-time initial ADMIN account provisioned successfully");
    }
}
