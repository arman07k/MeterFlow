package com.meterflow.identity.internal;

import com.meterflow.identity.api.PlatformRole;
import com.meterflow.identity.api.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform_role", nullable = false, length = 20)
    private PlatformRole platformRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserEntity() {
    }

    UserEntity(
            String normalizedEmail,
            String fullName,
            String passwordHash,
            PlatformRole platformRole,
            Instant now
    ) {
        this.email = normalizedEmail;
        this.fullName = fullName;
        this.passwordHash = passwordHash;
        this.platformRole = platformRole;
        this.status = UserStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    String getEmail() {
        return email;
    }

    String getFullName() {
        return fullName;
    }

    String getPasswordHash() {
        return passwordHash;
    }

    PlatformRole getPlatformRole() {
        return platformRole;
    }

    UserStatus getStatus() {
        return status;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    void updateStatus(UserStatus newStatus, Instant now) {
        this.status = newStatus;
        this.updatedAt = now;
    }
}
