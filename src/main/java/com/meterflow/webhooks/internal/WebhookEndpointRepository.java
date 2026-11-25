package com.meterflow.webhooks.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface WebhookEndpointRepository extends JpaRepository<WebhookEndpointEntity, UUID> {

    Optional<WebhookEndpointEntity> findByIdAndDeletedAtIsNull(UUID id);

    List<WebhookEndpointEntity> findByCustomerIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID customerId);

    List<WebhookEndpointEntity> findByCustomerIdAndActiveTrueAndDeletedAtIsNull(UUID customerId);

    Page<WebhookEndpointEntity> findByDeletedAtIsNullOrderByCreatedAtDesc(Pageable pageable);
}
