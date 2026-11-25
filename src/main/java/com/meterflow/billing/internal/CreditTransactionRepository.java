package com.meterflow.billing.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface CreditTransactionRepository extends JpaRepository<CreditTransactionEntity, UUID> {

    Optional<CreditTransactionEntity> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);

    Page<CreditTransactionEntity> findByCustomerIdOrderByCreatedAtDesc(UUID customerId, Pageable pageable);
}
