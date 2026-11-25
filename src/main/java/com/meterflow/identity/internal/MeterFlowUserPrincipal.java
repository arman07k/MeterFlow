package com.meterflow.identity.internal;

import com.meterflow.identity.api.AuthenticatedActor;
import com.meterflow.identity.api.PlatformRole;
import com.meterflow.identity.api.UserStatus;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Immutable Spring Security principal backed by persistent PostgreSQL identity records.
 */
final class MeterFlowUserPrincipal implements UserDetails {

    private final UUID userId;
    private final String email;
    private final String fullName;
    private final String passwordHash;
    private final PlatformRole role;
    private final UserStatus status;
    private final Set<UUID> authorizedCustomerIds;
    private final Collection<? extends GrantedAuthority> authorities;

    MeterFlowUserPrincipal(
            UUID userId,
            String email,
            String fullName,
            String passwordHash,
            PlatformRole role,
            UserStatus status,
            Set<UUID> authorizedCustomerIds
    ) {
        this.userId = userId;
        this.email = email;
        this.fullName = fullName;
        this.passwordHash = passwordHash;
        this.role = role;
        this.status = status;
        this.authorizedCustomerIds = authorizedCustomerIds == null ? Set.of() : Set.copyOf(authorizedCustomerIds);
        this.authorities = List.of(new SimpleGrantedAuthority(role.authority()));
    }

    UUID userId() {
        return userId;
    }

    String email() {
        return email;
    }

    String fullName() {
        return fullName;
    }

    PlatformRole role() {
        return role;
    }

    UserStatus status() {
        return status;
    }

    Set<UUID> authorizedCustomerIds() {
        return authorizedCustomerIds;
    }

    AuthenticatedActor toAuthenticatedActor(Set<UUID> currentAuthorizedCustomerIds, UserStatus currentStatus) {
        return new AuthenticatedActor(userId, email, fullName, role, currentStatus, currentAuthorizedCustomerIds);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return status != UserStatus.SUSPENDED;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return status != UserStatus.DISABLED;
    }
}
