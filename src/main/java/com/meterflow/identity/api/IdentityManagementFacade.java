package com.meterflow.identity.api;

import com.meterflow.shared.SupportedCurrency;
import java.util.UUID;

/**
 * Public command facade for managing customers, application users, and customer memberships.
 */
public interface IdentityManagementFacade {

    CustomerSummary createCustomer(String externalKey, String name, SupportedCurrency currency);

    CustomerSummary updateCustomerStatus(UUID customerId, CustomerStatus status);

    UserSummary createUser(String email, String fullName, String rawPassword, PlatformRole role);

    UserSummary updateUserStatus(UUID userId, UserStatus status);

    CustomerMembershipSummary assignMembership(UUID userId, UUID customerId, MembershipRole membershipRole);

    CustomerMembershipSummary revokeMembership(UUID userId, UUID customerId);
}
