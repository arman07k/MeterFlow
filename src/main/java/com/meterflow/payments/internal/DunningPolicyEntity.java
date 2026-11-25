package com.meterflow.payments.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "dunning_policies")
class DunningPolicyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "policy_code", nullable = false, length = 64, updatable = false)
    private String policyCode;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retry_offsets_hours", nullable = false, columnDefinition = "jsonb")
    private List<Integer> retryOffsetsHours = new ArrayList<>();

    @Column(name = "suspension_offset_hours", nullable = false)
    private int suspensionOffsetHours;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DunningPolicyEntity() {
    }

    DunningPolicyEntity(
            String policyCode,
            String name,
            List<Integer> retryOffsetsHours,
            int suspensionOffsetHours,
            Instant now
    ) {
        this.policyCode = policyCode;
        this.name = name;
        this.retryOffsetsHours = new ArrayList<>(retryOffsetsHours);
        this.suspensionOffsetHours = suspensionOffsetHours;
        this.active = true;
        this.createdAt = now;
        this.updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    String getPolicyCode() {
        return policyCode;
    }

    String getName() {
        return name;
    }

    List<Integer> getRetryOffsetsHours() {
        return retryOffsetsHours == null ? List.of() : List.copyOf(retryOffsetsHours);
    }

    int getSuspensionOffsetHours() {
        return suspensionOffsetHours;
    }

    boolean isActive() {
        return active;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    void deactivate(Instant now) {
        this.active = false;
        this.updatedAt = now;
    }
}
