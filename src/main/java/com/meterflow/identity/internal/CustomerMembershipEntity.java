package com.meterflow.identity.internal;

import com.meterflow.identity.api.MembershipRole;
import com.meterflow.identity.api.MembershipStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "customer_memberships",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_customer_memberships_user_customer",
                columnNames = { "user_id", "customer_id" }
        )
)
class CustomerMembershipEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private UserEntity user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false)
    private CustomerEntity customer;

    @Enumerated(EnumType.STRING)
    @Column(name = "membership_role", nullable = false, length = 20)
    private MembershipRole membershipRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MembershipStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CustomerMembershipEntity() {
    }

    CustomerMembershipEntity(
            UserEntity user,
            CustomerEntity customer,
            MembershipRole membershipRole,
            Instant now
    ) {
        this.user = user;
        this.customer = customer;
        this.membershipRole = membershipRole;
        this.status = MembershipStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getUserId() {
        return user.getId();
    }

    UUID getCustomerId() {
        return customer.getId();
    }

    MembershipRole getMembershipRole() {
        return membershipRole;
    }

    MembershipStatus getStatus() {
        return status;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    void activate(MembershipRole newRole, Instant now) {
        this.membershipRole = newRole;
        this.status = MembershipStatus.ACTIVE;
        this.updatedAt = now;
    }

    void revoke(Instant now) {
        this.status = MembershipStatus.REVOKED;
        this.updatedAt = now;
    }
}
