package com.meterflow.identity.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CustomerRepository extends JpaRepository<CustomerEntity, UUID> {

    Optional<CustomerEntity> findByExternalKey(String externalKey);

    boolean existsByExternalKey(String externalKey);

    Page<CustomerEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("""
            SELECT c FROM CustomerEntity c
            WHERE LOWER(c.name) LIKE LOWER(CONCAT('%', :term, '%'))
               OR LOWER(c.externalKey) LIKE LOWER(CONCAT('%', :term, '%'))
            ORDER BY c.createdAt DESC
            """)
    Page<CustomerEntity> searchByNameOrExternalKey(@Param("term") String term, Pageable pageable);
}
