package com.meterflow.catalog.internal;

import com.meterflow.catalog.api.ChargeDefinition;
import com.meterflow.catalog.api.ChargeTierDefinition;
import com.meterflow.catalog.api.FreeAllowanceDefinition;
import com.meterflow.catalog.api.MinimumCommitmentDefinition;
import com.meterflow.catalog.api.PlanVersionDetail;
import com.meterflow.shared.Money;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Batch-loading PostgreSQL projection loader for plan versions and all child pricing tables
 * (constant 5 queries per load, zero N+1 queries).
 */
@Component
class PlanVersionLoader {

    private final PlanVersionRepository planVersionRepository;
    private final PlanChargeRepository planChargeRepository;
    private final ChargeTierRepository chargeTierRepository;
    private final FreeAllowanceRepository freeAllowanceRepository;
    private final MinimumCommitmentRepository minimumCommitmentRepository;

    PlanVersionLoader(
            PlanVersionRepository planVersionRepository,
            PlanChargeRepository planChargeRepository,
            ChargeTierRepository chargeTierRepository,
            FreeAllowanceRepository freeAllowanceRepository,
            MinimumCommitmentRepository minimumCommitmentRepository
    ) {
        this.planVersionRepository = planVersionRepository;
        this.planChargeRepository = planChargeRepository;
        this.chargeTierRepository = chargeTierRepository;
        this.freeAllowanceRepository = freeAllowanceRepository;
        this.minimumCommitmentRepository = minimumCommitmentRepository;
    }

    @Transactional(readOnly = true)
    Optional<PlanVersionDetail> loadAuthoritativePlanVersion(UUID planVersionId) {
        if (planVersionId == null) {
            return Optional.empty();
        }
        return planVersionRepository.findByIdWithPlan(planVersionId)
                .map(version -> assembleDetails(List.of(version)).getFirst());
    }

    @Transactional(readOnly = true)
    List<PlanVersionDetail> loadVersionsForPlan(UUID planId) {
        List<PlanVersionEntity> versions = planVersionRepository.findByPlanIdWithPlanOrderByVersionNumberDesc(planId);
        if (versions.isEmpty()) {
            return List.of();
        }
        return assembleDetails(versions);
    }

    PlanVersionDetail assembleSingle(PlanVersionEntity version) {
        return assembleDetails(List.of(version)).getFirst();
    }

    private List<PlanVersionDetail> assembleDetails(List<PlanVersionEntity> versions) {
        List<UUID> versionIds = versions.stream().map(PlanVersionEntity::getId).toList();

        List<PlanChargeEntity> allCharges = planChargeRepository
                .findByPlanVersionIdInOrderByPlanVersionIdAscDisplayOrderAsc(versionIds);
        List<UUID> chargeIds = allCharges.stream().map(PlanChargeEntity::getId).toList();

        Map<UUID, List<ChargeTierDefinition>> tiersByCharge = new HashMap<>();
        if (!chargeIds.isEmpty()) {
            for (ChargeTierEntity tier : chargeTierRepository
                    .findByPlanChargeIdInOrderByPlanChargeIdAscTierOrderAsc(chargeIds)) {
                tiersByCharge.computeIfAbsent(tier.getPlanChargeId(), k -> new ArrayList<>())
                        .add(new ChargeTierDefinition(
                                tier.getTierOrder(),
                                tier.getLowerBound(),
                                tier.getUpperBound(),
                                tier.getUnitPrice(),
                                tier.getFlatFee(),
                                tier.getDescription()
                        ));
            }
        }

        Map<UUID, List<ChargeDefinition>> chargesByVersion = new HashMap<>();
        for (PlanChargeEntity charge : allCharges) {
            chargesByVersion.computeIfAbsent(charge.getPlanVersionId(), k -> new ArrayList<>())
                    .add(new ChargeDefinition(
                            charge.getId(),
                            charge.getDisplayOrder(),
                            charge.getChargeCode(),
                            charge.getDescription(),
                            charge.getChargeType(),
                            charge.getMetricId(),
                            charge.getFlatAmount(),
                            charge.getUnitPrice(),
                            charge.getPackageSize(),
                            charge.getPackagePrice(),
                            tiersByCharge.getOrDefault(charge.getId(), List.of()),
                            charge.getChargeConfig()
                    ));
        }

        Map<UUID, List<FreeAllowanceDefinition>> allowancesByVersion = new HashMap<>();
        for (FreeAllowanceEntity allowance : freeAllowanceRepository
                .findByPlanVersionIdInOrderByPlanVersionIdAscCreatedAtAsc(versionIds)) {
            allowancesByVersion.computeIfAbsent(allowance.getPlanVersionId(), k -> new ArrayList<>())
                    .add(new FreeAllowanceDefinition(
                            allowance.getId(),
                            allowance.getMetricId(),
                            allowance.getTargetChargeId(),
                            allowance.getFreeQuantity(),
                            allowance.getResetPeriod()
                    ));
        }

        Map<UUID, List<MinimumCommitmentDefinition>> commitmentsByVersion = new HashMap<>();
        for (MinimumCommitmentEntity commitment : minimumCommitmentRepository
                .findByPlanVersionIdInOrderByPlanVersionIdAscCreatedAtAsc(versionIds)) {
            commitmentsByVersion.computeIfAbsent(commitment.getPlanVersionId(), k -> new ArrayList<>())
                    .add(new MinimumCommitmentDefinition(
                            commitment.getId(),
                            commitment.getPlanChargeId(),
                            Money.of(commitment.getMinimumAmount(), commitment.getCurrency())
                    ));
        }

        List<PlanVersionDetail> results = new ArrayList<>(versions.size());
        for (PlanVersionEntity pv : versions) {
            PricingPlanEntity plan = pv.getPlan();
            results.add(new PlanVersionDetail(
                    pv.getId(),
                    plan.getId(),
                    plan.getPlanCode(),
                    plan.getName(),
                    plan.getCurrency(),
                    pv.getVersionNumber(),
                    pv.getStatus(),
                    pv.getEffectiveFrom(),
                    pv.getPublishedAt(),
                    pv.getClonedFromVersionId(),
                    chargesByVersion.getOrDefault(pv.getId(), List.of()),
                    allowancesByVersion.getOrDefault(pv.getId(), List.of()),
                    commitmentsByVersion.getOrDefault(pv.getId(), List.of()),
                    pv.getCreatedAt(),
                    pv.getUpdatedAt()
            ));
        }
        return results;
    }
}
