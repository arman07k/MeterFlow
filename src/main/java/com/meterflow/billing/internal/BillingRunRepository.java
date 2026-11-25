package com.meterflow.billing.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface BillingRunRepository extends JpaRepository<BillingRunEntity, UUID> {

    Optional<BillingRunEntity> findByIdempotencyKey(String idempotencyKey);

    Page<BillingRunEntity> findAllByOrderByStartedAtDesc(Pageable pageable);
}
