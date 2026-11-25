package com.meterflow.identity.internal;

import com.meterflow.identity.api.CustomerMembershipSummary;
import com.meterflow.identity.api.CustomerStatus;
import com.meterflow.identity.api.CustomerSummary;
import com.meterflow.identity.api.DuplicateCustomerKeyException;
import com.meterflow.identity.api.DuplicateEmailException;
import com.meterflow.identity.api.IdentityManagementFacade;
import com.meterflow.identity.api.IdentityQueryFacade;
import com.meterflow.identity.api.IdentityResourceNotFoundException;
import com.meterflow.identity.api.InvalidMembershipException;
import com.meterflow.identity.api.MembershipRole;
import com.meterflow.identity.api.MembershipStatus;
import com.meterflow.identity.api.PlatformRole;
import com.meterflow.identity.api.UserStatus;
import com.meterflow.identity.api.UserSummary;
import com.meterflow.shared.InvalidCurrencyException;
import com.meterflow.shared.SupportedCurrency;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional identity command and query service encapsulating customers, users, and memberships.
 */
@Service
class IdentityService implements IdentityManagementFacade, IdentityQueryFacade {

    static final int MIN_PASSWORD_LENGTH = 12;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final CustomerRepository customerRepository;
    private final UserRepository userRepository;
    private final CustomerMembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    IdentityService(
            CustomerRepository customerRepository,
            UserRepository userRepository,
            CustomerMembershipRepository membershipRepository,
            PasswordEncoder passwordEncoder,
            Clock clock
    ) {
        this.customerRepository = customerRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Override
    @Transactional
    public CustomerSummary createCustomer(String externalKey, String name, SupportedCurrency currency) {
        String normalizedKey = requireNonBlank(externalKey, "Customer externalKey must not be null or blank", 100);
        String normalizedName = requireNonBlank(name, "Customer name must not be null or blank", 200);
        if (currency == null) {
            throw new InvalidCurrencyException("Customer currency must not be null");
        }
        if (customerRepository.existsByExternalKey(normalizedKey)) {
            throw new DuplicateCustomerKeyException("Customer external key already exists");
        }

        Instant now = nowUtc();
        CustomerEntity entity = new CustomerEntity(normalizedKey, normalizedName, currency, now);
        try {
            CustomerEntity saved = customerRepository.saveAndFlush(entity);
            return toCustomerSummary(saved);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateCustomerKeyException("Customer external key already exists");
        }
    }

    @Override
    @Transactional
    public CustomerSummary updateCustomerStatus(UUID customerId, CustomerStatus status) {
        if (customerId == null) {
            throw new IllegalArgumentException("customerId must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("Customer status must not be null");
        }
        CustomerEntity customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IdentityResourceNotFoundException("Customer not found"));
        customer.updateStatus(status, nowUtc());
        return toCustomerSummary(customerRepository.saveAndFlush(customer));
    }

    @Override
    @Transactional
    public UserSummary createUser(String email, String fullName, String rawPassword, PlatformRole role) {
        String normalizedEmail = normalizeAndValidateEmail(email);
        String normalizedName = requireNonBlank(fullName, "User fullName must not be null or blank", 200);
        validateRawPassword(rawPassword);
        if (role == null) {
            throw new IllegalArgumentException("Platform role must not be null");
        }
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new DuplicateEmailException("An account with this email already exists");
        }

        String passwordHash = passwordEncoder.encode(rawPassword);
        Instant now = nowUtc();
        UserEntity entity = new UserEntity(normalizedEmail, normalizedName, passwordHash, role, now);
        try {
            UserEntity saved = userRepository.saveAndFlush(entity);
            return toUserSummary(saved);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateEmailException("An account with this email already exists");
        }
    }

    @Override
    @Transactional
    public UserSummary updateUserStatus(UUID userId, UserStatus status) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("User status must not be null");
        }
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new IdentityResourceNotFoundException("User not found"));
        user.updateStatus(status, nowUtc());
        return toUserSummary(userRepository.saveAndFlush(user));
    }

    @Override
    @Transactional
    public CustomerMembershipSummary assignMembership(
            UUID userId,
            UUID customerId,
            MembershipRole membershipRole
    ) {
        if (userId == null || customerId == null) {
            throw new InvalidMembershipException("userId and customerId must not be null");
        }
        if (membershipRole == null) {
            throw new InvalidMembershipException("membershipRole must not be null");
        }

        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new IdentityResourceNotFoundException("User not found"));
        if (user.getPlatformRole() != PlatformRole.CUSTOMER) {
            throw new InvalidMembershipException(
                    "Customer memberships may only be assigned to CUSTOMER platform users"
            );
        }

        CustomerEntity customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IdentityResourceNotFoundException("Customer not found"));
        if (customer.getStatus() == CustomerStatus.CLOSED) {
            throw new InvalidMembershipException("Cannot assign membership to a CLOSED customer");
        }

        Instant now = nowUtc();
        CustomerMembershipEntity membership = membershipRepository
                .findByUserIdAndCustomerId(userId, customerId)
                .map(existing -> {
                    existing.activate(membershipRole, now);
                    return existing;
                })
                .orElseGet(() -> new CustomerMembershipEntity(user, customer, membershipRole, now));

        CustomerMembershipEntity saved = membershipRepository.saveAndFlush(membership);
        return toMembershipSummary(saved);
    }

    @Override
    @Transactional
    public CustomerMembershipSummary revokeMembership(UUID userId, UUID customerId) {
        if (userId == null || customerId == null) {
            throw new InvalidMembershipException("userId and customerId must not be null");
        }
        CustomerMembershipEntity membership = membershipRepository
                .findByUserIdAndCustomerId(userId, customerId)
                .orElseThrow(() -> new IdentityResourceNotFoundException("Customer membership not found"));
        membership.revoke(nowUtc());
        return toMembershipSummary(membershipRepository.saveAndFlush(membership));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserSummary> findUserById(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return userRepository.findById(userId).map(IdentityService::toUserSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserSummary> findUserByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return userRepository.findByEmail(email.trim().toLowerCase(Locale.ROOT))
                .map(IdentityService::toUserSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerSummary> findCustomerById(UUID customerId) {
        if (customerId == null) {
            return Optional.empty();
        }
        return customerRepository.findById(customerId).map(IdentityService::toCustomerSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, CustomerSummary> findCustomersByIds(Collection<UUID> customerIds) {
        if (customerIds == null || customerIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, CustomerSummary> result = new HashMap<>();
        for (CustomerEntity entity : customerRepository.findAllById(customerIds)) {
            result.put(entity.getId(), toCustomerSummary(entity));
        }
        return Map.copyOf(result);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerSummary> findCustomerByExternalKey(String externalKey) {
        if (externalKey == null || externalKey.isBlank()) {
            return Optional.empty();
        }
        return customerRepository.findByExternalKey(externalKey.trim())
                .map(IdentityService::toCustomerSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CustomerSummary> listCustomers(String search, Pageable pageable) {
        if (search == null || search.isBlank()) {
            return customerRepository.findAllByOrderByCreatedAtDesc(pageable)
                    .map(IdentityService::toCustomerSummary);
        }
        return customerRepository.searchByNameOrExternalKey(search.trim(), pageable)
                .map(IdentityService::toCustomerSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public long countCustomers() {
        return customerRepository.count();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerMembershipSummary> findActiveMembershipsForUser(UUID userId) {
        if (userId == null) {
            return List.of();
        }
        return membershipRepository.findSummariesByUserIdAndStatus(userId, MembershipStatus.ACTIVE);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> findAuthorizedCustomerIds(UUID userId) {
        if (userId == null) {
            return Set.of();
        }
        return Set.copyOf(membershipRepository.findActiveCustomerIdsByUserId(userId));
    }

    static String normalizeAndValidateEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email must not be null or blank");
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 255 || !EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid email format");
        }
        return normalized;
    }

    static void validateRawPassword(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("Password must not be null or blank");
        }
        if (rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters long"
            );
        }
    }

    private Instant nowUtc() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static String requireNonBlank(String value, String message, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException("Value exceeds maximum length of " + maxLength);
        }
        return trimmed;
    }

    private static CustomerSummary toCustomerSummary(CustomerEntity entity) {
        return new CustomerSummary(
                entity.getId(),
                entity.getExternalKey(),
                entity.getName(),
                entity.getCurrency(),
                entity.getStatus(),
                entity.getCreatedAt()
        );
    }

    private static UserSummary toUserSummary(UserEntity entity) {
        return new UserSummary(
                entity.getId(),
                entity.getEmail(),
                entity.getFullName(),
                entity.getPlatformRole(),
                entity.getStatus(),
                entity.getCreatedAt()
        );
    }

    private static CustomerMembershipSummary toMembershipSummary(CustomerMembershipEntity entity) {
        return new CustomerMembershipSummary(
                entity.getId(),
                entity.getUserId(),
                entity.getCustomerId(),
                entity.getMembershipRole(),
                entity.getStatus(),
                entity.getCreatedAt()
        );
    }
}
