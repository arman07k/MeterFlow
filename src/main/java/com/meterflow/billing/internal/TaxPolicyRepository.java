package com.meterflow.billing.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface TaxPolicyRepository extends JpaRepository<TaxPolicyEntity, UUID> {

    Optional<TaxPolicyEntity> findByPolicyCode(String policyCode);

    Optional<TaxPolicyEntity> findFirstByCustomerIdAndActiveTrue(UUID customerId);

    Optional<TaxPolicyEntity> findFirstByCustomerIdIsNullAndActiveTrue();

    List<TaxPolicyEntity> findAllByOrderByCreatedAtDesc();
}
