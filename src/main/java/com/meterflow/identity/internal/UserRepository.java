package com.meterflow.identity.internal;

import com.meterflow.identity.api.PlatformRole;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByEmail(String normalizedEmail);

    boolean existsByEmail(String normalizedEmail);

    boolean existsByPlatformRole(PlatformRole platformRole);
}
