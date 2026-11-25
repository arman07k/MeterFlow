package com.meterflow.identity.internal;

import com.meterflow.identity.api.CustomerMembershipSummary;
import com.meterflow.identity.api.MembershipStatus;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CustomerMembershipRepository extends JpaRepository<CustomerMembershipEntity, UUID> {

    Optional<CustomerMembershipEntity> findByUserIdAndCustomerId(UUID userId, UUID customerId);

    boolean existsByUserIdAndCustomerIdAndStatus(UUID userId, UUID customerId, MembershipStatus status);

    @Query("""
            SELECT new com.meterflow.identity.api.CustomerMembershipSummary(
                m.id,
                m.user.id,
                m.customer.id,
                m.membershipRole,
                m.status,
                m.createdAt
            )
            FROM CustomerMembershipEntity m
            WHERE m.user.id = :userId
              AND m.status = :status
            ORDER BY m.createdAt ASC
            """)
    List<CustomerMembershipSummary> findSummariesByUserIdAndStatus(
            @Param("userId") UUID userId,
            @Param("status") MembershipStatus status
    );

    @Query("""
            SELECT m.customer.id
            FROM CustomerMembershipEntity m
            WHERE m.user.id = :userId
              AND m.status = com.meterflow.identity.api.MembershipStatus.ACTIVE
            """)
    Set<UUID> findActiveCustomerIdsByUserId(@Param("userId") UUID userId);
}
