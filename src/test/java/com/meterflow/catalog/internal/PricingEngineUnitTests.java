package com.meterflow.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.meterflow.catalog.api.AllowanceResetPeriod;
import com.meterflow.catalog.api.ChargeCalculationContext;
import com.meterflow.catalog.api.ChargeCalculationOutcome;
import com.meterflow.catalog.api.ChargeCalculationStrategy;
import com.meterflow.catalog.api.ChargeDefinition;
import com.meterflow.catalog.api.ChargeTierDefinition;
import com.meterflow.catalog.api.ChargeTypes;
import com.meterflow.catalog.api.FreeAllowanceDefinition;
import com.meterflow.catalog.api.InvalidCatalogConfigurationException;
import com.meterflow.catalog.api.InvalidRatingContextException;
import com.meterflow.catalog.api.MetricUsageInput;
import com.meterflow.catalog.api.MinimumCommitmentDefinition;
import com.meterflow.catalog.api.PlanRatingResult;
import com.meterflow.catalog.api.PlanVersionDetail;
import com.meterflow.catalog.api.PlanVersionStatus;
import com.meterflow.catalog.api.RatingRequest;
import com.meterflow.shared.CurrencyMismatchException;
import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PricingEngineUnitTests {

    private static final Instant START = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-12-01T00:00:00Z");

    private final List<ChargeCalculationStrategy> mandatoryStrategies = List.of(
            new FlatFeeChargeStrategy(),
            new PerUnitChargeStrategy(),
            new GraduatedTiersChargeStrategy(),
            new VolumeTiersChargeStrategy(),
            new PackageChargeStrategy(),
            new PerSeatChargeStrategy()
    );

    private PlanRatingComposite createEngine(List<ChargeCalculationStrategy> strategies) {
        return new PlanRatingComposite(mock(PlanVersionLoader.class), new ChargeStrategyRegistry(strategies));
    }

    @Test
    void calculatesAllSixMandatoryChargeTypesAccuratelyInBdtAndUsd() {
        PlanRatingComposite engine = createEngine(mandatoryStrategies);
        UUID apiMetric = UUID.randomUUID();
        UUID storageMetric = UUID.randomUUID();
        UUID smsMetric = UUID.randomUUID();

        ChargeDefinition flat = new ChargeDefinition(UUID.randomUUID(), 1, "base", "Monthly Base",
                ChargeTypes.FLAT_FEE, null, new BigDecimal("500.00"), null, null, null, List.of(), Map.of());
        ChargeDefinition perUnit = new ChargeDefinition(UUID.randomUUID(), 2, "storage", "Storage GB",
                ChargeTypes.PER_UNIT, storageMetric, null, new BigDecimal("2.50"), null, null, List.of(), Map.of());
        ChargeDefinition pkg = new ChargeDefinition(UUID.randomUUID(), 3, "sms_pkg", "Per 1000 SMS",
                ChargeTypes.PACKAGE, smsMetric, null, null, new BigDecimal("1000"), new BigDecimal("120.00"), List.of(), Map.of());
        ChargeDefinition seats = new ChargeDefinition(UUID.randomUUID(), 4, "seats", "Per Seat",
                ChargeTypes.PER_SEAT, null, null, new BigDecimal("150.00"), null, null, List.of(), Map.of());
        ChargeDefinition graduated = new ChargeDefinition(UUID.randomUUID(), 5, "api_grad", "API Graduated",
                ChargeTypes.GRADUATED_TIERS, apiMetric, null, null, null, null,
                List.of(
                        new ChargeTierDefinition(1, BigDecimal.ZERO, new BigDecimal("100"), new BigDecimal("1.00"), BigDecimal.ZERO, "T1"),
                        new ChargeTierDefinition(2, new BigDecimal("100"), null, new BigDecimal("0.50"), BigDecimal.ZERO, "T2")
                ), Map.of());

        PlanVersionDetail version = planVersion(SupportedCurrency.BDT, List.of(flat, perUnit, pkg, seats, graduated), List.of(), List.of());

        PlanRatingResult result = engine.ratePlanVersion(version, new RatingRequest(
                UUID.randomUUID(), version.id(), SupportedCurrency.BDT, START, END, 4,
                List.of(
                        new MetricUsageInput(storageMetric, new BigDecimal("10")),   // 25.00
                        new MetricUsageInput(smsMetric, new BigDecimal("2001")),     // CEIL(2001/1000)=3 * 120 = 360.00
                        new MetricUsageInput(apiMetric, new BigDecimal("150"))       // 100*1 + 50*0.5 = 125.00
                ), null
        ));

        // 500 + 25 + 360 + (4*150=600) + 125 = 1610.00 BDT
        assertThat(result.calculatedUsageAmount()).isEqualTo(Money.bdt("1610.0000"));
        assertThat(result.finalPersistedAmount()).isEqualTo(Money.bdt("1610.0000"));
        assertThat(result.finalPayableAmount()).isEqualTo(Money.bdt("1610.00"));
        assertThat(result.chargeBreakdowns()).hasSize(5);
    }

    @Test
    void distinguishesGraduatedVsVolumeTiersAcrossAllBoundariesAndBoundedMax() {
        PlanRatingComposite engine = createEngine(mandatoryStrategies);
        UUID metricId = UUID.randomUUID();
        List<ChargeTierDefinition> boundedTiers = List.of(
                new ChargeTierDefinition(1, BigDecimal.ZERO, new BigDecimal("100"), new BigDecimal("10.00"), BigDecimal.ZERO, "0-100"),
                new ChargeTierDefinition(2, new BigDecimal("100"), new BigDecimal("500"), new BigDecimal("8.00"), BigDecimal.ZERO, "100-500")
        );
        List<ChargeTierDefinition> unboundedTiers = List.of(
                new ChargeTierDefinition(1, BigDecimal.ZERO, new BigDecimal("100"), new BigDecimal("10.00"), BigDecimal.ZERO, "0-100"),
                new ChargeTierDefinition(2, new BigDecimal("100"), new BigDecimal("500"), new BigDecimal("8.00"), BigDecimal.ZERO, "100-500"),
                new ChargeTierDefinition(3, new BigDecimal("500"), null, new BigDecimal("5.00"), BigDecimal.ZERO, "500+")
        );

        PlanVersionDetail gradBounded = planVersion(SupportedCurrency.USD,
                List.of(new ChargeDefinition(UUID.randomUUID(), 1, "g", null, ChargeTypes.GRADUATED_TIERS, metricId,
                        null, null, null, null, boundedTiers, Map.of())), List.of(), List.of());
        PlanVersionDetail volBounded = planVersion(SupportedCurrency.USD,
                List.of(new ChargeDefinition(UUID.randomUUID(), 1, "v", null, ChargeTypes.VOLUME_TIERS, metricId,
                        null, null, null, null, boundedTiers, Map.of())), List.of(), List.of());
        PlanVersionDetail gradUnbounded = planVersion(SupportedCurrency.USD,
                List.of(new ChargeDefinition(UUID.randomUUID(), 1, "gu", null, ChargeTypes.GRADUATED_TIERS, metricId,
                        null, null, null, null, unboundedTiers, Map.of())), List.of(), List.of());
        PlanVersionDetail volUnbounded = planVersion(SupportedCurrency.USD,
                List.of(new ChargeDefinition(UUID.randomUUID(), 1, "vu", null, ChargeTypes.VOLUME_TIERS, metricId,
                        null, null, null, null, unboundedTiers, Map.of())), List.of(), List.of());

        // Zero usage
        assertThat(rateMetric(engine, gradBounded, metricId, "0").finalPayableAmount()).isEqualTo(Money.usd("0.00"));
        assertThat(rateMetric(engine, volBounded, metricId, "0").finalPayableAmount()).isEqualTo(Money.usd("0.00"));

        // Below first tier boundary (50)
        assertThat(rateMetric(engine, gradBounded, metricId, "50").finalPayableAmount()).isEqualTo(Money.usd("500.00"));
        assertThat(rateMetric(engine, volBounded, metricId, "50").finalPayableAmount()).isEqualTo(Money.usd("500.00"));

        // Exactly at boundary (100)
        assertThat(rateMetric(engine, gradBounded, metricId, "100").finalPayableAmount()).isEqualTo(Money.usd("1000.00"));
        assertThat(rateMetric(engine, volBounded, metricId, "100").finalPayableAmount()).isEqualTo(Money.usd("1000.00"));

        // Just above boundary (101): Graduated = 100*10 + 1*8 = 1008; Volume = 101*8 = 808
        PlanRatingResult grad101 = rateMetric(engine, gradBounded, metricId, "101");
        PlanRatingResult vol101 = rateMetric(engine, volBounded, metricId, "101");
        assertThat(grad101.finalPayableAmount()).isEqualTo(Money.usd("1008.00"));
        assertThat(grad101.chargeBreakdowns().getFirst().appliedTiers()).hasSize(2);
        assertThat(vol101.finalPayableAmount()).isEqualTo(Money.usd("808.00"));
        assertThat(vol101.chargeBreakdowns().getFirst().appliedTiers()).hasSize(1);

        // At bounded max (500)
        assertThat(rateMetric(engine, gradBounded, metricId, "500").finalPayableAmount()).isEqualTo(Money.usd("4200.00"));
        assertThat(rateMetric(engine, volBounded, metricId, "500").finalPayableAmount()).isEqualTo(Money.usd("4000.00"));

        // Exceeding bounded max (501) fails explicitly
        assertThatThrownBy(() -> rateMetric(engine, gradBounded, metricId, "501"))
                .isInstanceOf(InvalidRatingContextException.class);
        assertThatThrownBy(() -> rateMetric(engine, volBounded, metricId, "501"))
                .isInstanceOf(InvalidRatingContextException.class);

        // Unbounded final tier (600): Graduated = 100*10 + 400*8 + 100*5 = 4700; Volume = 600*5 = 3000
        assertThat(rateMetric(engine, gradUnbounded, metricId, "600").finalPayableAmount()).isEqualTo(Money.usd("4700.00"));
        assertThat(rateMetric(engine, volUnbounded, metricId, "600").finalPayableAmount()).isEqualTo(Money.usd("3000.00"));
    }

    @Test
    void enforcesCeilingPackageRoundingAndPerSeatQuantityVariations() {
        PlanRatingComposite engine = createEngine(mandatoryStrategies);
        UUID metricId = UUID.randomUUID();

        PlanVersionDetail pkgVersion = planVersion(SupportedCurrency.BDT,
                List.of(new ChargeDefinition(UUID.randomUUID(), 1, "pkg", "Per 1,000 units", ChargeTypes.PACKAGE, metricId,
                        null, null, new BigDecimal("1000"), new BigDecimal("50.00"), List.of(), Map.of())),
                List.of(), List.of());

        assertThat(rateMetric(engine, pkgVersion, metricId, "0").finalPayableAmount()).isEqualTo(Money.bdt("0.00"));
        assertThat(rateMetric(engine, pkgVersion, metricId, "1").finalPayableAmount()).isEqualTo(Money.bdt("50.00"));
        assertThat(rateMetric(engine, pkgVersion, metricId, "999").finalPayableAmount()).isEqualTo(Money.bdt("50.00"));
        assertThat(rateMetric(engine, pkgVersion, metricId, "1000").finalPayableAmount()).isEqualTo(Money.bdt("50.00"));
        assertThat(rateMetric(engine, pkgVersion, metricId, "1001").finalPayableAmount()).isEqualTo(Money.bdt("100.00"));

        UUID seatChargeId = UUID.randomUUID();
        PlanVersionDetail seatVersion = planVersion(SupportedCurrency.USD,
                List.of(new ChargeDefinition(seatChargeId, 1, "seats", "Contracted Seats", ChargeTypes.PER_SEAT, null,
                        null, new BigDecimal("12.50"), null, null, List.of(), Map.of())),
                List.of(new FreeAllowanceDefinition(UUID.randomUUID(), null, seatChargeId, new BigDecimal("2"), AllowanceResetPeriod.BILLING_PERIOD)),
                List.of());

        PlanRatingResult twoSeats = engine.ratePlanVersion(seatVersion, new RatingRequest(
                UUID.randomUUID(), seatVersion.id(), SupportedCurrency.USD, START, END, 2, List.of(), null));
        PlanRatingResult tenSeats = engine.ratePlanVersion(seatVersion, new RatingRequest(
                UUID.randomUUID(), seatVersion.id(), SupportedCurrency.USD, START, END, 10, List.of(), null));

        assertThat(twoSeats.finalPayableAmount()).isEqualTo(Money.usd("0.00"));
        assertThat(tenSeats.finalPayableAmount()).isEqualTo(Money.usd("100.00")); // (10 - 2) * 12.50

        // Missing contracted seatQuantity fails explicitly
        assertThatThrownBy(() -> engine.ratePlanVersion(seatVersion, new RatingRequest(
                UUID.randomUUID(), seatVersion.id(), SupportedCurrency.USD, START, END, null, List.of(), null)))
                .isInstanceOf(InvalidRatingContextException.class);
    }

    @Test
    void preventsDuplicateAllowanceConsumptionAndAppliesPlanCommitmentOnce() {
        PlanRatingComposite engine = createEngine(mandatoryStrategies);
        UUID sharedMetric = UUID.randomUUID();
        UUID c1Id = UUID.randomUUID();
        UUID c2Id = UUID.randomUUID();

        // Two charges both referencing sharedMetric; only ONE 500-unit metric allowance configured
        ChargeDefinition c1 = new ChargeDefinition(c1Id, 1, "primary", null, ChargeTypes.PER_UNIT, sharedMetric,
                null, new BigDecimal("1.00"), null, null, List.of(), Map.of());
        ChargeDefinition c2 = new ChargeDefinition(c2Id, 2, "surcharge", null, ChargeTypes.PER_UNIT, sharedMetric,
                null, new BigDecimal("0.50"), null, null, List.of(), Map.of());

        FreeAllowanceDefinition sharedAllowance = new FreeAllowanceDefinition(
                UUID.randomUUID(), sharedMetric, null, new BigDecimal("500"), AllowanceResetPeriod.BILLING_PERIOD);
        MinimumCommitmentDefinition planMin = new MinimumCommitmentDefinition(
                UUID.randomUUID(), null, Money.bdt("1000.00"));

        PlanVersionDetail version = planVersion(SupportedCurrency.BDT, List.of(c1, c2), List.of(sharedAllowance), List.of(planMin));

        // Usage = 300: Charge 1 consumes 300 free -> 0 billed; Charge 2 gets ONLY remaining 200 free -> 100 billed * 0.50 = 50.00
        // Plan min = 1000 -> true-up = 950.00 (applied ONCE at plan level, not per charge)
        PlanRatingResult underMin = rateMetric(engine, version, sharedMetric, "300");
        assertThat(underMin.chargeBreakdowns().get(0).freeAllowanceQuantity()).isEqualByComparingTo("300");
        assertThat(underMin.chargeBreakdowns().get(0).billableQuantity()).isEqualByComparingTo("0");
        assertThat(underMin.chargeBreakdowns().get(1).freeAllowanceQuantity()).isEqualByComparingTo("200");
        assertThat(underMin.chargeBreakdowns().get(1).billableQuantity()).isEqualByComparingTo("100");
        assertThat(underMin.calculatedUsageAmount()).isEqualTo(Money.bdt("50.0000"));
        assertThat(underMin.planMinimumCommitment()).isEqualTo(Money.bdt("1000.00"));
        assertThat(underMin.planMinimumTrueUp()).isEqualTo(Money.bdt("950.0000"));
        assertThat(underMin.finalPayableAmount()).isEqualTo(Money.bdt("1000.00"));

        // Usage = 2000: Charge 1 consumes all 500 free -> 1500*1 = 1500; Charge 2 gets 0 free -> 2000*0.5 = 1000; total = 2500 >= 1000 -> true-up = 0
        PlanRatingResult overMin = rateMetric(engine, version, sharedMetric, "2000");
        assertThat(overMin.chargeBreakdowns().get(0).freeAllowanceQuantity()).isEqualByComparingTo("500");
        assertThat(overMin.chargeBreakdowns().get(1).freeAllowanceQuantity()).isEqualByComparingTo("0");
        assertThat(overMin.calculatedUsageAmount()).isEqualTo(Money.bdt("2500.0000"));
        assertThat(overMin.planMinimumTrueUp()).isEqualTo(Money.bdt("0.0000"));
        assertThat(overMin.finalPayableAmount()).isEqualTo(Money.bdt("2500.00"));
    }

    @Test
    void validatesTierConfigurationsRejectsCurrencyMismatchAndNegativeUsage() {
        assertThatThrownBy(() -> TierValidationSupport.validateAndSortTiers(List.of(
                new ChargeTierDefinition(1, BigDecimal.TEN, new BigDecimal("100"), BigDecimal.ONE, BigDecimal.ZERO, null)
        ))).isInstanceOf(InvalidCatalogConfigurationException.class);

        assertThatThrownBy(() -> TierValidationSupport.validateAndSortTiers(List.of(
                new ChargeTierDefinition(1, BigDecimal.ZERO, new BigDecimal("100"), BigDecimal.ONE, BigDecimal.ZERO, null),
                new ChargeTierDefinition(2, new BigDecimal("90"), null, BigDecimal.ONE, BigDecimal.ZERO, null)
        ))).isInstanceOf(InvalidCatalogConfigurationException.class);

        assertThatThrownBy(() -> TierValidationSupport.validateAndSortTiers(List.of(
                new ChargeTierDefinition(1, BigDecimal.ZERO, new BigDecimal("100"), BigDecimal.ONE, BigDecimal.ZERO, null),
                new ChargeTierDefinition(2, new BigDecimal("150"), null, BigDecimal.ONE, BigDecimal.ZERO, null)
        ))).isInstanceOf(InvalidCatalogConfigurationException.class);

        assertThatThrownBy(() -> TierValidationSupport.validateAndSortTiers(List.of(
                new ChargeTierDefinition(1, BigDecimal.ZERO, null, BigDecimal.ONE, BigDecimal.ZERO, null),
                new ChargeTierDefinition(2, new BigDecimal("100"), null, BigDecimal.ONE, BigDecimal.ZERO, null)
        ))).isInstanceOf(InvalidCatalogConfigurationException.class);

        PlanRatingComposite engine = createEngine(mandatoryStrategies);
        UUID metricId = UUID.randomUUID();
        PlanVersionDetail bdtVersion = planVersion(SupportedCurrency.BDT,
                List.of(new ChargeDefinition(UUID.randomUUID(), 1, "u", null, ChargeTypes.PER_UNIT, metricId,
                        null, BigDecimal.ONE, null, null, List.of(), Map.of())), List.of(), List.of());

        assertThatThrownBy(() -> engine.ratePlanVersion(bdtVersion, new RatingRequest(
                UUID.randomUUID(), bdtVersion.id(), SupportedCurrency.USD, START, END, null, List.of(), null)))
                .isInstanceOf(CurrencyMismatchException.class);

        assertThatThrownBy(() -> rateMetric(engine, bdtVersion, metricId, "-5"))
                .isInstanceOf(InvalidRatingContextException.class);
    }

    @Test
    void supportsAddingCustomTestOnlyChargeStrategyWithoutModifyingExistingStrategies() {
        // Test-only 7th charge strategy proving Open/Closed Principle extensibility (ADR-001, ADR-011)
        ChargeCalculationStrategy testPercentageFeeStrategy = new ChargeCalculationStrategy() {
            @Override
            public String supportedChargeType() {
                return "TEST_PERCENTAGE_FEE";
            }

            @Override
            public void validate(ChargeDefinition charge, SupportedCurrency planCurrency) {
                if (charge.chargeConfig().get("ratePercent") == null) {
                    throw new InvalidCatalogConfigurationException("ratePercent is required");
                }
            }

            @Override
            public ChargeCalculationOutcome calculate(ChargeCalculationContext context) {
                BigDecimal ratePercent = new BigDecimal(String.valueOf(context.charge().chargeConfig().get("ratePercent")));
                BigDecimal factor = ratePercent.movePointLeft(2);
                Money amount = Money.of(context.billableQuantity(), context.currency()).multiply(factor);
                return new ChargeCalculationOutcome(amount, factor, List.of(), Map.of("customStrategy", "TEST_PERCENTAGE_FEE"));
            }
        };

        List<ChargeCalculationStrategy> extended = new java.util.ArrayList<>(mandatoryStrategies);
        extended.add(testPercentageFeeStrategy);
        PlanRatingComposite engine = createEngine(extended);

        UUID gmvMetric = UUID.randomUUID();
        ChargeDefinition baseFlat = new ChargeDefinition(UUID.randomUUID(), 1, "flat", null,
                ChargeTypes.FLAT_FEE, null, new BigDecimal("100.00"), null, null, null, List.of(), Map.of());
        ChargeDefinition customPercent = new ChargeDefinition(UUID.randomUUID(), 2, "take_rate", null,
                "TEST_PERCENTAGE_FEE", gmvMetric, null, null, null, null, List.of(), Map.of("ratePercent", "2.5"));

        PlanVersionDetail version = planVersion(SupportedCurrency.BDT, List.of(baseFlat, customPercent), List.of(), List.of());
        PlanRatingResult result = rateMetric(engine, version, gmvMetric, "10000");

        // 100.00 + (10000 * 2.5% = 250.00) = 350.00 BDT
        assertThat(result.finalPayableAmount()).isEqualTo(Money.bdt("350.00"));
        assertThat(result.chargeBreakdowns()).hasSize(2);
        assertThat(result.chargeBreakdowns().get(1).metadata()).containsEntry("customStrategy", "TEST_PERCENTAGE_FEE");
    }

    private static PlanRatingResult rateMetric(
            PlanRatingComposite engine,
            PlanVersionDetail version,
            UUID metricId,
            String quantity
    ) {
        return engine.ratePlanVersion(version, new RatingRequest(
                UUID.randomUUID(),
                version.id(),
                version.currency(),
                START,
                END,
                null,
                List.of(new MetricUsageInput(metricId, new BigDecimal(quantity))),
                null
        ));
    }

    private static PlanVersionDetail planVersion(
            SupportedCurrency currency,
            List<ChargeDefinition> charges,
            List<FreeAllowanceDefinition> allowances,
            List<MinimumCommitmentDefinition> commitments
    ) {
        return new PlanVersionDetail(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "test-plan",
                "Test Plan",
                currency,
                1,
                PlanVersionStatus.PUBLISHED,
                START,
                START,
                null,
                charges,
                allowances,
                commitments,
                START,
                START
        );
    }
}
