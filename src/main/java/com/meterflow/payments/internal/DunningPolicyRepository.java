package com.meterflow.payments.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface DunningPolicyRepository extends JpaRepository<DunningPolicyEntity, UUID> {

    Optional<DunningPolicyEntity> findByPolicyCode(String policyCode);

    Optional<DunningPolicyEntity> findFirstByActiveTrue();

    List<DunningPolicyEntity> findAllByOrderByCreatedAtDesc();
}
