package com.meterflow.identity.internal;

import com.meterflow.identity.api.PlatformRole;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Database-backed Spring Security {@link UserDetailsService} normalizing emails and enforcing
 * persisted user status (DISABLED/SUSPENDED users cannot authenticate).
 */
@Service
class DatabaseUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final CustomerMembershipRepository membershipRepository;

    DatabaseUserDetailsService(
            UserRepository userRepository,
            CustomerMembershipRepository membershipRepository
    ) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String rawEmail) throws UsernameNotFoundException {
        if (rawEmail == null || rawEmail.isBlank()) {
            throw new UsernameNotFoundException("User credentials not found");
        }
        String normalizedEmail = rawEmail.trim().toLowerCase(Locale.ROOT);
        UserEntity user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new UsernameNotFoundException("User credentials not found"));

        Set<UUID> customerIds = user.getPlatformRole() == PlatformRole.CUSTOMER
                ? membershipRepository.findActiveCustomerIdsByUserId(user.getId())
                : Set.of();

        return new MeterFlowUserPrincipal(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getPasswordHash(),
                user.getPlatformRole(),
                user.getStatus(),
                customerIds
        );
    }
}
