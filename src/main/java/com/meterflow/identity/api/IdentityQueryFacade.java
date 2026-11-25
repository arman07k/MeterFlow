package com.meterflow.identity.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Public read-only query facade for identity and customer membership lookups across modules.
 */
public interface IdentityQueryFacade {

    Optional<UserSummary> findUserById(UUID userId);

    Optional<UserSummary> findUserByEmail(String email);

    Optional<CustomerSummary> findCustomerById(UUID customerId);

    Map<UUID, CustomerSummary> findCustomersByIds(Collection<UUID> customerIds);

    Optional<CustomerSummary> findCustomerByExternalKey(String externalKey);

    Page<CustomerSummary> listCustomers(String search, Pageable pageable);

    long countCustomers();

    List<CustomerMembershipSummary> findActiveMembershipsForUser(UUID userId);

    Set<UUID> findAuthorizedCustomerIds(UUID userId);
}
