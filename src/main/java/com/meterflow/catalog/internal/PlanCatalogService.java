package com.meterflow.catalog.internal;

import com.meterflow.catalog.api.AllowanceResetPeriod;
import com.meterflow.catalog.api.CatalogResourceNotFoundException;
import com.meterflow.catalog.api.ChargeCalculationStrategy;
import com.meterflow.catalog.api.ChargeDefinition;
import com.meterflow.catalog.api.ChargeTierDefinition;
import com.meterflow.catalog.api.ConfigureAllowanceCommand;
import com.meterflow.catalog.api.ConfigureChargeCommand;
import com.meterflow.catalog.api.ConfigureCommitmentCommand;
import com.meterflow.catalog.api.CreatePlanCommand;
import com.meterflow.catalog.api.DuplicateCatalogKeyException;
import com.meterflow.catalog.api.ImmutablePlanVersionException;
import com.meterflow.catalog.api.InvalidCatalogConfigurationException;
import com.meterflow.catalog.api.PlanCatalogFacade;
import com.meterflow.catalog.api.PlanVersionDetail;
import com.meterflow.catalog.api.PlanVersionStatus;
import com.meterflow.catalog.api.PricingPlanSummary;
import com.meterflow.catalog.api.UpdateDraftPlanVersionCommand;
import com.meterflow.shared.CurrencyMismatchException;
import com.meterflow.shared.InvalidCurrencyException;
import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PlanCatalogService implements PlanCatalogFacade {

    private final PricingPlanRepository planRepository;
    private final PlanVersionRepository planVersionRepository;
    private final PlanChargeRepository planChargeRepository;
    private final ChargeTierRepository chargeTierRepository;
    private final FreeAllowanceRepository freeAllowanceRepository;
    private final MinimumCommitmentRepository minimumCommitmentRepository;
    private final BillableMetricRepository metricRepository;
    private final ChargeStrategyRegistry strategyRegistry;
    private final PlanVersionLoader planVersionLoader;
    private final CatalogCacheHelper cacheHelper;
    private final Clock clock;

    PlanCatalogService(
            PricingPlanRepository planRepository,
            PlanVersionRepository planVersionRepository,
            PlanChargeRepository planChargeRepository,
            ChargeTierRepository chargeTierRepository,
            FreeAllowanceRepository freeAllowanceRepository,
            MinimumCommitmentRepository minimumCommitmentRepository,
            BillableMetricRepository metricRepository,
            ChargeStrategyRegistry strategyRegistry,
            PlanVersionLoader planVersionLoader,
            CatalogCacheHelper cacheHelper,
            Clock clock
    ) {
        this.planRepository = planRepository;
        this.planVersionRepository = planVersionRepository;
        this.planChargeRepository = planChargeRepository;
        this.chargeTierRepository = chargeTierRepository;
        this.freeAllowanceRepository = freeAllowanceRepository;
        this.minimumCommitmentRepository = minimumCommitmentRepository;
        this.metricRepository = metricRepository;
        this.strategyRegistry = strategyRegistry;
        this.planVersionLoader = planVersionLoader;
        this.cacheHelper = cacheHelper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public PlanVersionDetail createPlanWithDraft(CreatePlanCommand command) {
        if (command == null) {
            throw new InvalidCatalogConfigurationException("CreatePlanCommand must not be null");
        }
        String normalizedCode = requireNonBlank(command.planCode(), "planCode must not be blank", 80)
                .toLowerCase(Locale.ROOT);
        String name = requireNonBlank(command.name(), "Plan name must not be blank", 160);
        if (command.currency() == null) {
            throw new InvalidCurrencyException("Plan currency must not be null");
        }
        if (planRepository.existsByPlanCode(normalizedCode)) {
            throw new DuplicateCatalogKeyException("Pricing plan code already exists");
        }

        Instant now = nowUtc();
        PricingPlanEntity plan = new PricingPlanEntity(
                normalizedCode,
                name,
                trimNullable(command.description()),
                command.currency(),
                now
        );
        PricingPlanEntity savedPlan;
        try {
            savedPlan = planRepository.saveAndFlush(plan);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateCatalogKeyException("Pricing plan code already exists");
        }

        PlanVersionEntity draft = new PlanVersionEntity(
                savedPlan,
                1,
                truncateNullable(command.initialEffectiveFrom()),
                null,
                now
        );
        PlanVersionEntity savedDraft = planVersionRepository.saveAndFlush(draft);
        return planVersionLoader.assembleSingle(savedDraft);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PricingPlanSummary> listPlans(Pageable pageable) {
        return planRepository.findAll(pageable).map(PlanCatalogService::toPlanSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PricingPlanSummary> findPlanById(UUID planId) {
        if (planId == null) {
            return Optional.empty();
        }
        return planRepository.findById(planId).map(PlanCatalogService::toPlanSummary);
    }

    @Override
    @Transactional
    public PlanVersionDetail createDraftVersion(UUID planId, Instant effectiveFrom) {
        if (planId == null) {
            throw new InvalidCatalogConfigurationException("planId must not be null");
        }
        PricingPlanEntity plan = planRepository.findByIdForUpdate(planId)
                .orElseThrow(() -> new CatalogResourceNotFoundException("Pricing plan not found"));

        int nextVersionNumber = planVersionRepository.findMaxVersionNumberByPlanId(planId) + 1;
        Instant now = nowUtc();
        PlanVersionEntity draft = new PlanVersionEntity(
                plan,
                nextVersionNumber,
                truncateNullable(effectiveFrom),
                null,
                now
        );
        PlanVersionEntity saved = planVersionRepository.saveAndFlush(draft);
        return planVersionLoader.assembleSingle(saved);
    }

    @Override
    @Transactional
    public PlanVersionDetail updateDraftVersion(UUID planVersionId, UpdateDraftPlanVersionCommand command) {
        if (planVersionId == null || command == null) {
            throw new InvalidCatalogConfigurationException("planVersionId and UpdateDraftPlanVersionCommand must not be null");
        }
        PlanVersionEntity version = planVersionRepository.findByIdWithPlan(planVersionId)
                .orElseThrow(() -> new CatalogResourceNotFoundException("Plan version not found"));
        if (!version.getStatus().isMutable()) {
            throw new ImmutablePlanVersionException(
                    "Plan version " + version.getVersionNumber() + " is " + version.getStatus() + " and cannot be modified"
            );
        }

        SupportedCurrency planCurrency = version.getPlan().getCurrency();
        Instant now = nowUtc();
        version.updateEffectiveFrom(truncateNullable(command.effectiveFrom()), now);
        planVersionRepository.saveAndFlush(version);

        // Clear existing draft child configuration cleanly before inserting validated replacement
        minimumCommitmentRepository.deleteByPlanVersionId(planVersionId);
        freeAllowanceRepository.deleteByPlanVersionId(planVersionId);
        List<PlanChargeEntity> existingCharges = planChargeRepository.findByPlanVersionIdOrderByDisplayOrderAsc(planVersionId);
        if (!existingCharges.isEmpty()) {
            List<UUID> existingChargeIds = existingCharges.stream().map(PlanChargeEntity::getId).toList();
            chargeTierRepository.deleteByPlanChargeIdIn(existingChargeIds);
            planChargeRepository.deleteByPlanVersionId(planVersionId);
        }
        planChargeRepository.flush();

        // Validate and persist charges + tiers
        Map<String, PlanChargeEntity> chargesByCode = new HashMap<>();
        Set<Integer> usedOrders = new HashSet<>();
        for (ConfigureChargeCommand chargeCmd : command.charges()) {
            String normalizedCode = requireNonBlank(chargeCmd.chargeCode(), "chargeCode must not be blank", 80)
                    .toLowerCase(Locale.ROOT);
            if (chargeCmd.displayOrder() < 1) {
                throw new InvalidCatalogConfigurationException("Charge displayOrder must be >= 1");
            }
            if (!usedOrders.add(chargeCmd.displayOrder())) {
                throw new InvalidCatalogConfigurationException("Duplicate charge displayOrder: " + chargeCmd.displayOrder());
            }
            if (chargesByCode.containsKey(normalizedCode)) {
                throw new InvalidCatalogConfigurationException("Duplicate chargeCode in plan version: " + normalizedCode);
            }
            if (chargeCmd.metricId() != null) {
                BillableMetricEntity metric = metricRepository.findById(chargeCmd.metricId())
                        .orElseThrow(() -> new CatalogResourceNotFoundException("Referenced billable metric not found"));
                if (!metric.isActive()) {
                    throw new InvalidCatalogConfigurationException(
                            "Cannot bind inactive billable metric '" + metric.getMetricKey() + "' to plan charge"
                    );
                }
            }

            ChargeCalculationStrategy strategy = strategyRegistry.resolve(chargeCmd.chargeType());
            String canonicalType = strategy.supportedChargeType().trim().toUpperCase(Locale.ROOT);
            ChargeDefinition validationView = new ChargeDefinition(
                    null,
                    chargeCmd.displayOrder(),
                    normalizedCode,
                    trimNullable(chargeCmd.description()),
                    canonicalType,
                    chargeCmd.metricId(),
                    chargeCmd.flatAmount(),
                    chargeCmd.unitPrice(),
                    chargeCmd.packageSize(),
                    chargeCmd.packagePrice(),
                    chargeCmd.tiers(),
                    chargeCmd.chargeConfig()
            );
            strategy.validate(validationView, planCurrency);

            PlanChargeEntity chargeEntity = new PlanChargeEntity(
                    version,
                    chargeCmd.displayOrder(),
                    normalizedCode,
                    trimNullable(chargeCmd.description()),
                    canonicalType,
                    chargeCmd.metricId(),
                    chargeCmd.flatAmount(),
                    chargeCmd.unitPrice(),
                    chargeCmd.packageSize(),
                    chargeCmd.packagePrice(),
                    chargeCmd.chargeConfig(),
                    now
            );
            PlanChargeEntity savedCharge = planChargeRepository.saveAndFlush(chargeEntity);
            chargesByCode.put(normalizedCode, savedCharge);

            if (!chargeCmd.tiers().isEmpty()) {
                List<ChargeTierEntity> tierEntities = new ArrayList<>(chargeCmd.tiers().size());
                for (ChargeTierDefinition tier : chargeCmd.tiers()) {
                    tierEntities.add(new ChargeTierEntity(
                            savedCharge,
                            tier.tierOrder(),
                            tier.lowerBound(),
                            tier.upperBound(),
                            tier.unitPrice(),
                            tier.flatFee(),
                            trimNullable(tier.description())
                    ));
                }
                chargeTierRepository.saveAllAndFlush(tierEntities);
            }
        }

        // Validate and persist free allowances
        for (ConfigureAllowanceCommand allowanceCmd : command.freeAllowances()) {
            if (allowanceCmd.freeQuantity() == null
                    || allowanceCmd.freeQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw new InvalidCatalogConfigurationException("Free allowance quantity must be > 0");
            }
            UUID targetChargeId = null;
            UUID metricId = allowanceCmd.metricId();
            if (allowanceCmd.targetChargeCode() != null && !allowanceCmd.targetChargeCode().isBlank()) {
                String code = allowanceCmd.targetChargeCode().trim().toLowerCase(Locale.ROOT);
                PlanChargeEntity target = chargesByCode.get(code);
                if (target == null) {
                    throw new InvalidCatalogConfigurationException(
                            "Free allowance references unknown chargeCode: " + code
                    );
                }
                targetChargeId = target.getId();
                if (metricId == null) {
                    metricId = target.getMetricId();
                }
            }
            if (metricId == null && targetChargeId == null) {
                throw new InvalidCatalogConfigurationException(
                        "Free allowance must specify either metricId or targetChargeCode"
                );
            }
            if (metricId != null && !metricRepository.existsById(metricId)) {
                throw new CatalogResourceNotFoundException("Referenced billable metric not found for free allowance");
            }
            AllowanceResetPeriod resetPeriod = allowanceCmd.resetPeriod() == null
                    ? AllowanceResetPeriod.BILLING_PERIOD
                    : allowanceCmd.resetPeriod();

            freeAllowanceRepository.saveAndFlush(new FreeAllowanceEntity(
                    version,
                    metricId,
                    targetChargeId,
                    allowanceCmd.freeQuantity(),
                    resetPeriod,
                    now
            ));
        }

        // Validate and persist minimum commitments
        boolean hasPlanLevelCommitment = false;
        Set<UUID> chargeLevelCommitments = new HashSet<>();
        for (ConfigureCommitmentCommand commitCmd : command.minimumCommitments()) {
            Money minAmount = commitCmd.minimumAmount();
            if (minAmount == null || minAmount.isNegative()) {
                throw new InvalidCatalogConfigurationException("Minimum commitment amount must be non-null and >= 0");
            }
            if (minAmount.currency() != planCurrency) {
                throw new CurrencyMismatchException(planCurrency, minAmount.currency());
            }
            UUID targetChargeId = null;
            if (commitCmd.targetChargeCode() != null && !commitCmd.targetChargeCode().isBlank()) {
                String code = commitCmd.targetChargeCode().trim().toLowerCase(Locale.ROOT);
                PlanChargeEntity target = chargesByCode.get(code);
                if (target == null) {
                    throw new InvalidCatalogConfigurationException(
                            "Minimum commitment references unknown chargeCode: " + code
                    );
                }
                targetChargeId = target.getId();
                if (!chargeLevelCommitments.add(targetChargeId)) {
                    throw new InvalidCatalogConfigurationException(
                            "Duplicate minimum commitment configured for chargeCode: " + code
                    );
                }
            } else {
                if (hasPlanLevelCommitment) {
                    throw new InvalidCatalogConfigurationException(
                            "At most one plan-level minimum commitment may be configured per plan version"
                    );
                }
                hasPlanLevelCommitment = true;
            }

            minimumCommitmentRepository.saveAndFlush(new MinimumCommitmentEntity(
                    version,
                    targetChargeId,
                    minAmount.roundToPersisted().amount(),
                    planCurrency,
                    now
            ));
        }

        cacheHelper.evictPlanVersionAfterCommit(planVersionId);
        return planVersionLoader.assembleSingle(version);
    }

    @Override
    @Transactional
    public PlanVersionDetail publishVersion(UUID planVersionId) {
        if (planVersionId == null) {
            throw new InvalidCatalogConfigurationException("planVersionId must not be null");
        }
        PlanVersionEntity version = planVersionRepository.findByIdWithPlan(planVersionId)
                .orElseThrow(() -> new CatalogResourceNotFoundException("Plan version not found"));
        if (!version.getStatus().isMutable()) {
            throw new ImmutablePlanVersionException(
                    "Plan version " + version.getVersionNumber() + " is already " + version.getStatus()
            );
        }

        PlanVersionDetail detail = planVersionLoader.assembleSingle(version);
        if (detail.charges().isEmpty()) {
            throw new InvalidCatalogConfigurationException(
                    "Cannot publish plan version without at least one configured charge"
            );
        }
        SupportedCurrency planCurrency = detail.currency();
        for (ChargeDefinition charge : detail.charges()) {
            strategyRegistry.resolve(charge.chargeType()).validate(charge, planCurrency);
        }

        version.publish(nowUtc());
        planVersionRepository.saveAndFlush(version);
        cacheHelper.evictPlanVersionAfterCommit(planVersionId);
        return planVersionLoader.assembleSingle(version);
    }

    @Override
    @Transactional
    public PlanVersionDetail cloneVersionToDraft(UUID sourcePlanVersionId, Instant effectiveFrom) {
        if (sourcePlanVersionId == null) {
            throw new InvalidCatalogConfigurationException("sourcePlanVersionId must not be null");
        }
        PlanVersionDetail source = planVersionLoader.loadAuthoritativePlanVersion(sourcePlanVersionId)
                .orElseThrow(() -> new CatalogResourceNotFoundException("Source plan version not found"));

        PricingPlanEntity plan = planRepository.findByIdForUpdate(source.planId())
                .orElseThrow(() -> new CatalogResourceNotFoundException("Pricing plan not found"));

        int nextVersionNumber = planVersionRepository.findMaxVersionNumberByPlanId(plan.getId()) + 1;
        Instant now = nowUtc();

        PlanVersionEntity clonedVersion = planVersionRepository.saveAndFlush(new PlanVersionEntity(
                plan,
                nextVersionNumber,
                truncateNullable(effectiveFrom),
                source.id(),
                now
        ));

        Map<UUID, UUID> oldToNewChargeIds = new HashMap<>();
        for (ChargeDefinition srcCharge : source.charges()) {
            PlanChargeEntity newCharge = planChargeRepository.saveAndFlush(new PlanChargeEntity(
                    clonedVersion,
                    srcCharge.displayOrder(),
                    srcCharge.chargeCode(),
                    srcCharge.description(),
                    srcCharge.chargeType(),
                    srcCharge.metricId(),
                    srcCharge.flatAmount(),
                    srcCharge.unitPrice(),
                    srcCharge.packageSize(),
                    srcCharge.packagePrice(),
                    srcCharge.chargeConfig(),
                    now
            ));
            oldToNewChargeIds.put(srcCharge.id(), newCharge.getId());

            if (!srcCharge.tiers().isEmpty()) {
                List<ChargeTierEntity> clonedTiers = new ArrayList<>(srcCharge.tiers().size());
                for (ChargeTierDefinition t : srcCharge.tiers()) {
                    clonedTiers.add(new ChargeTierEntity(
                            newCharge,
                            t.tierOrder(),
                            t.lowerBound(),
                            t.upperBound(),
                            t.unitPrice(),
                            t.flatFee(),
                            t.description()
                    ));
                }
                chargeTierRepository.saveAllAndFlush(clonedTiers);
            }
        }

        for (var srcAllowance : source.freeAllowances()) {
            UUID mappedTargetChargeId = srcAllowance.targetChargeId() == null
                    ? null
                    : oldToNewChargeIds.get(srcAllowance.targetChargeId());
            freeAllowanceRepository.saveAndFlush(new FreeAllowanceEntity(
                    clonedVersion,
                    srcAllowance.metricId(),
                    mappedTargetChargeId,
                    srcAllowance.freeQuantity(),
                    srcAllowance.resetPeriod(),
                    now
            ));
        }

        for (var srcCommitment : source.minimumCommitments()) {
            UUID mappedChargeId = srcCommitment.planChargeId() == null
                    ? null
                    : oldToNewChargeIds.get(srcCommitment.planChargeId());
            minimumCommitmentRepository.saveAndFlush(new MinimumCommitmentEntity(
                    clonedVersion,
                    mappedChargeId,
                    srcCommitment.minimumAmount().roundToPersisted().amount(),
                    srcCommitment.minimumAmount().currency(),
                    now
            ));
        }

        return planVersionLoader.assembleSingle(clonedVersion);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PlanVersionDetail> listPlanVersions(UUID planId) {
        if (planId == null || !planRepository.existsById(planId)) {
            throw new CatalogResourceNotFoundException("Pricing plan not found");
        }
        return planVersionLoader.loadVersionsForPlan(planId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlanVersionDetail> findPlanVersionById(UUID planVersionId) {
        if (planVersionId == null) {
            return Optional.empty();
        }
        Optional<PlanVersionDetail> cached = cacheHelper.getPublishedPlanVersion(planVersionId);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<PlanVersionDetail> loaded = planVersionLoader.loadAuthoritativePlanVersion(planVersionId);
        loaded.ifPresent(cacheHelper::putIfPublished);
        return loaded;
    }

    private Instant nowUtc() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static Instant truncateNullable(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }

    private static String requireNonBlank(String value, String message, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new InvalidCatalogConfigurationException(message);
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new InvalidCatalogConfigurationException("Value exceeds maximum length of " + maxLength);
        }
        return trimmed;
    }

    private static String trimNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static PricingPlanSummary toPlanSummary(PricingPlanEntity plan) {
        return new PricingPlanSummary(
                plan.getId(),
                plan.getPlanCode(),
                plan.getName(),
                plan.getDescription(),
                plan.getCurrency(),
                plan.isActive(),
                plan.getCreatedAt(),
                plan.getUpdatedAt()
        );
    }
}
