package com.meterflow.identity.internal;

import com.meterflow.identity.api.CustomerStatus;
import com.meterflow.shared.SupportedCurrency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customers")
class CustomerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "external_key", nullable = false, unique = true, length = 100)
    private String externalKey;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency_code", nullable = false, length = 3)
    private SupportedCurrency currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CustomerStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CustomerEntity() {
    }

    CustomerEntity(String externalKey, String name, SupportedCurrency currency, Instant now) {
        this.externalKey = externalKey;
        this.name = name;
        this.currency = currency;
        this.status = CustomerStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    String getExternalKey() {
        return externalKey;
    }

    String getName() {
        return name;
    }

    SupportedCurrency getCurrency() {
        return currency;
    }

    CustomerStatus getStatus() {
        return status;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    void updateStatus(CustomerStatus newStatus, Instant now) {
        this.status = newStatus;
        this.updatedAt = now;
    }
}
