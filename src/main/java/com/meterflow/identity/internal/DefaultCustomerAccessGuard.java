package com.meterflow.identity.internal;

import com.meterflow.identity.api.AuthenticatedActor;
import com.meterflow.identity.api.CustomerAccessGuard;
import com.meterflow.identity.api.MembershipStatus;
import com.meterflow.identity.api.PlatformRole;
import com.meterflow.identity.api.UnauthorizedCustomerAccessException;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authoritative tenant-isolation and role guard reading live user status and active customer
 * memberships from PostgreSQL so revoked memberships or suspended users lose access immediately.
 */
@Service
class DefaultCustomerAccessGuard implements CustomerAccessGuard {

    private final UserRepository userRepository;
    private final CustomerMembershipRepository membershipRepository;

    DefaultCustomerAccessGuard(
            UserRepository userRepository,
            CustomerMembershipRepository membershipRepository
    ) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public AuthenticatedActor requireAuthenticatedActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new AuthenticationCredentialsNotFoundException("Authentication is required");
        }

        Object principal = authentication.getPrincipal();
        if (!(principal instanceof MeterFlowUserPrincipal userPrincipal)) {
            throw new UnauthorizedCustomerAccessException("Unsupported authentication principal");
        }

        UserEntity liveUser = userRepository.findById(userPrincipal.userId())
                .orElseThrow(() -> new UnauthorizedCustomerAccessException("Authenticated account is unavailable"));
        if (!liveUser.getStatus().canAuthenticate()) {
            throw new UnauthorizedCustomerAccessException("Authenticated account is not active");
        }

        Set<UUID> customerIds = liveUser.getPlatformRole() == PlatformRole.CUSTOMER
                ? membershipRepository.findActiveCustomerIdsByUserId(liveUser.getId())
                : Set.of();

        return new AuthenticatedActor(
                liveUser.getId(),
                liveUser.getEmail(),
                liveUser.getFullName(),
                liveUser.getPlatformRole(),
                liveUser.getStatus(),
                customerIds
        );
    }

    @Override
    @Transactional(readOnly = true)
    public void requireCustomerAccess(UUID targetCustomerId) {
        if (targetCustomerId == null) {
            throw new UnauthorizedCustomerAccessException("Access to the requested customer is denied");
        }
        AuthenticatedActor actor = requireAuthenticatedActor();
        if (actor.isPlatformStaff()) {
            return;
        }
        boolean activeMember = membershipRepository.existsByUserIdAndCustomerIdAndStatus(
                actor.userId(),
                targetCustomerId,
                MembershipStatus.ACTIVE
        );
        if (!activeMember) {
            throw new UnauthorizedCustomerAccessException("Access to the requested customer is denied");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canAccessCustomer(UUID targetCustomerId) {
        if (targetCustomerId == null) {
            return false;
        }
        try {
            requireCustomerAccess(targetCustomerId);
            return true;
        } catch (UnauthorizedCustomerAccessException | AuthenticationCredentialsNotFoundException ex) {
            return false;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> requireCustomerScope() {
        AuthenticatedActor actor = requireAuthenticatedActor();
        if (actor.role() != PlatformRole.CUSTOMER) {
            throw new UnauthorizedCustomerAccessException("Customer membership scope applies only to CUSTOMER users");
        }
        if (actor.authorizedCustomerIds().isEmpty()) {
            throw new UnauthorizedCustomerAccessException("No active customer memberships found for account");
        }
        return actor.authorizedCustomerIds();
    }

    @Override
    @Transactional(readOnly = true)
    public void requireAnyRole(PlatformRole... allowedRoles) {
        if (allowedRoles == null || allowedRoles.length == 0) {
            throw new IllegalArgumentException("At least one allowed role must be specified");
        }
        AuthenticatedActor actor = requireAuthenticatedActor();
        boolean permitted = Arrays.asList(allowedRoles).contains(actor.role());
        if (!permitted) {
            throw new UnauthorizedCustomerAccessException("Insufficient role permissions for requested operation");
        }
    }
}
