package com.meterflow.subscriptions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterflow.shared.BillingInterval;
import com.meterflow.shared.BillingPeriod;
import com.meterflow.shared.CurrencyMismatchException;
import com.meterflow.shared.Money;
import com.meterflow.subscriptions.api.InvalidSubscriptionRequestException;
import com.meterflow.subscriptions.api.SubscriptionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class BillingPeriodAndProrationUnitTests {

    @Test
    void handlesMonthEndClampingAndLeapYearsDeterministically() {
        ZoneId utc = ZoneId.of("UTC");

        // Jan 31, 2026 -> Feb 28, 2026 (non-leap year: 28 days)
        BillingPeriod nonLeapFeb = BillingPeriod.startingAt(
                Instant.parse("2026-01-31T00:00:00Z"),
                BillingInterval.MONTHLY,
                utc
        );
        assertThat(nonLeapFeb.end()).isEqualTo(Instant.parse("2026-02-28T00:00:00Z"));
        assertThat(nonLeapFeb.totalSeconds()).isEqualTo(28L * 86_400L);

        // Jan 31, 2024 -> Feb 29, 2024 (leap year: 29 days)
        BillingPeriod leapFeb = BillingPeriod.startingAt(
                Instant.parse("2024-01-31T00:00:00Z"),
                BillingInterval.MONTHLY,
                utc
        );
        assertThat(leapFeb.end()).isEqualTo(Instant.parse("2024-02-29T00:00:00Z"));
        assertThat(leapFeb.totalSeconds()).isEqualTo(29L * 86_400L);

        // Leap day annual anchor: Feb 29, 2024 -> Feb 28, 2025 (365 days)
        BillingPeriod leapAnnual = BillingPeriod.startingAt(
                Instant.parse("2024-02-29T00:00:00Z"),
                BillingInterval.ANNUAL,
                utc
        );
        assertThat(leapAnnual.end()).isEqualTo(Instant.parse("2025-02-28T00:00:00Z"));
        assertThat(leapAnnual.totalSeconds()).isEqualTo(365L * 86_400L);

        // Annual spanning leap day: Mar 1, 2023 -> Mar 1, 2024 (366 days)
        BillingPeriod spanningLeapDay = BillingPeriod.startingAt(
                Instant.parse("2023-03-01T00:00:00Z"),
                BillingInterval.ANNUAL,
                utc
        );
        assertThat(spanningLeapDay.totalSeconds()).isEqualTo(366L * 86_400L);
    }

    @Test
    void handlesTimezoneAndDstTransitionsWithExactUtcSeconds() {
        // America/New_York springs forward on 2026-03-08 (March has 31 local days - 1 hour = 743 hours)
        ZoneId newYork = ZoneId.of("America/New_York");
        BillingPeriod springForwardMonth = BillingPeriod.startingAt(
                Instant.parse("2026-03-01T05:00:00Z"), // 2026-03-01T00:00:00-05:00
                BillingInterval.MONTHLY,
                newYork
        );
        assertThat(springForwardMonth.end()).isEqualTo(Instant.parse("2026-04-01T04:00:00Z")); // 2026-04-01T00:00:00-04:00
        assertThat(springForwardMonth.totalSeconds()).isEqualTo((31L * 24L - 1L) * 3_600L);

        // America/New_York falls back on 2026-11-01 (November has 30 local days + 1 hour = 721 hours)
        BillingPeriod fallBackMonth = BillingPeriod.startingAt(
                Instant.parse("2026-11-01T04:00:00Z"), // 2026-11-01T00:00:00-04:00
                BillingInterval.MONTHLY,
                newYork
        );
        assertThat(fallBackMonth.end()).isEqualTo(Instant.parse("2026-12-01T05:00:00Z")); // 2026-12-01T00:00:00-05:00
        assertThat(fallBackMonth.totalSeconds()).isEqualTo((30L * 24L + 1L) * 3_600L);

        // Asia/Dhaka (UTC+6, no DST)
        ZoneId dhaka = BillingPeriod.parseTimezone("Asia/Dhaka");
        BillingPeriod dhakaMonth = BillingPeriod.startingAt(
                Instant.parse("2026-04-01T00:00:00Z"),
                BillingInterval.MONTHLY,
                dhaka
        );
        assertThat(dhakaMonth.totalSeconds()).isEqualTo(30L * 86_400L);

        assertThatThrownBy(() -> BillingPeriod.parseTimezone("Invalid/Timezone"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void computesExactMidCycleUpgradeDowngradeAndEdgeBoundaryProrationsForBdtAndUsd() {
        BillingPeriod thirtyDayPeriod = BillingPeriod.startingAt(
                Instant.parse("2026-04-01T00:00:00Z"),
                BillingInterval.MONTHLY,
                ZoneId.of("UTC")
        );

        // Exact 50% midpoint upgrade in BDT: 1000 -> 2500 => credit 500, debit 1250, net +750
        Instant midpoint = Instant.parse("2026-04-16T00:00:00Z");
        ProrationCalculator.CalculationResult bdtUpgrade = ProrationCalculator.calculate(
                thirtyDayPeriod,
                midpoint,
                Money.bdt("1000.00"),
                Money.bdt("2500.00"),
                List.of("api_calls_usage")
        );
        assertThat(bdtUpgrade.totalPeriodSeconds()).isEqualTo(30L * 86_400L);
        assertThat(bdtUpgrade.remainingSeconds()).isEqualTo(15L * 86_400L);
        assertThat(bdtUpgrade.prorationFactor()).isEqualByComparingTo("0.50000000");
        assertThat(bdtUpgrade.creditAmount()).isEqualTo(Money.bdt("500.0000"));
        assertThat(bdtUpgrade.debitAmount()).isEqualTo(Money.bdt("1250.0000"));
        assertThat(bdtUpgrade.netProrationAmount()).isEqualTo(Money.bdt("750.0000"));
        assertThat(bdtUpgrade.nonProratableUsageCharges()).containsExactly("api_calls_usage");
        assertThat(bdtUpgrade.calculationPolicy()).isEqualTo(ProrationCalculator.POLICY_EXACT_SECONDS_V1);

        // Non-terminating 1/3 remaining downgrade in USD: 100.00 -> 40.00
        // credit = 100 * 10 / 30 = 33.3333, debit = 40 * 10 / 30 = 13.3333, net = -20.0000
        Instant twentyDaysIn = Instant.parse("2026-04-21T00:00:00Z");
        ProrationCalculator.CalculationResult usdDowngrade = ProrationCalculator.calculate(
                thirtyDayPeriod,
                twentyDaysIn,
                Money.usd("100.00"),
                Money.usd("40.00"),
                List.of()
        );
        assertThat(usdDowngrade.prorationFactor()).isEqualByComparingTo(new BigDecimal("0.33333333"));
        assertThat(usdDowngrade.creditAmount()).isEqualTo(Money.usd("33.3333"));
        assertThat(usdDowngrade.debitAmount()).isEqualTo(Money.usd("13.3333"));
        assertThat(usdDowngrade.netProrationAmount()).isEqualTo(Money.usd("-20.0000"));

        // 0% remaining (effectiveAt == periodEnd) -> all zero
        ProrationCalculator.CalculationResult zeroRemaining = ProrationCalculator.calculate(
                thirtyDayPeriod,
                thirtyDayPeriod.end(),
                Money.usd("100.00"),
                Money.usd("300.00"),
                List.of()
        );
        assertThat(zeroRemaining.remainingSeconds()).isZero();
        assertThat(zeroRemaining.prorationFactor()).isEqualByComparingTo("0.00000000");
        assertThat(zeroRemaining.creditAmount()).isEqualTo(Money.usd("0.0000"));
        assertThat(zeroRemaining.debitAmount()).isEqualTo(Money.usd("0.0000"));
        assertThat(zeroRemaining.netProrationAmount()).isEqualTo(Money.usd("0.0000"));

        // 100% remaining (effectiveAt == periodStart) -> full period difference
        ProrationCalculator.CalculationResult fullRemaining = ProrationCalculator.calculate(
                thirtyDayPeriod,
                thirtyDayPeriod.start(),
                Money.usd("100.00"),
                Money.usd("300.00"),
                List.of()
        );
        assertThat(fullRemaining.prorationFactor()).isEqualByComparingTo("1.00000000");
        assertThat(fullRemaining.creditAmount()).isEqualTo(Money.usd("100.0000"));
        assertThat(fullRemaining.debitAmount()).isEqualTo(Money.usd("300.0000"));
        assertThat(fullRemaining.netProrationAmount()).isEqualTo(Money.usd("200.0000"));
    }

    @Test
    void rejectsCurrencyMismatchAndInvalidProrationBoundaries() {
        BillingPeriod period = BillingPeriod.startingAt(
                Instant.parse("2026-04-01T00:00:00Z"),
                BillingInterval.MONTHLY,
                ZoneId.of("UTC")
        );

        assertThatThrownBy(() -> ProrationCalculator.calculate(
                period,
                Instant.parse("2026-04-10T00:00:00Z"),
                Money.bdt("100.00"),
                Money.usd("100.00"),
                List.of()
        )).isInstanceOf(CurrencyMismatchException.class);

        assertThatThrownBy(() -> ProrationCalculator.calculate(
                period,
                Instant.parse("2026-03-31T23:59:59Z"),
                Money.usd("100.00"),
                Money.usd("200.00"),
                List.of()
        )).isInstanceOf(InvalidSubscriptionRequestException.class);

        assertThatThrownBy(() -> ProrationCalculator.calculate(
                period,
                Instant.parse("2026-05-01T00:00:01Z"),
                Money.usd("100.00"),
                Money.usd("200.00"),
                List.of()
        )).isInstanceOf(InvalidSubscriptionRequestException.class);
    }

    @Test
    void enforcesAuthoritativeSubscriptionStatusTransitionMatrix() {
        assertThat(SubscriptionStatus.TRIALING.canTransitionTo(SubscriptionStatus.ACTIVE)).isTrue();
        assertThat(SubscriptionStatus.TRIALING.canTransitionTo(SubscriptionStatus.EXPIRED)).isTrue();
        assertThat(SubscriptionStatus.TRIALING.canTransitionTo(SubscriptionStatus.CANCELLED)).isTrue();
        assertThat(SubscriptionStatus.TRIALING.canTransitionTo(SubscriptionStatus.PAUSED)).isFalse();

        assertThat(SubscriptionStatus.ACTIVE.canTransitionTo(SubscriptionStatus.PAUSED)).isTrue();
        assertThat(SubscriptionStatus.ACTIVE.canTransitionTo(SubscriptionStatus.PAST_DUE)).isTrue();
        assertThat(SubscriptionStatus.ACTIVE.canTransitionTo(SubscriptionStatus.CANCELLED)).isTrue();
        assertThat(SubscriptionStatus.ACTIVE.canTransitionTo(SubscriptionStatus.TRIALING)).isFalse();
        assertThat(SubscriptionStatus.ACTIVE.canTransitionTo(SubscriptionStatus.EXPIRED)).isFalse();

        assertThat(SubscriptionStatus.PAUSED.canTransitionTo(SubscriptionStatus.ACTIVE)).isTrue();
        assertThat(SubscriptionStatus.PAUSED.canTransitionTo(SubscriptionStatus.CANCELLED)).isTrue();
        assertThat(SubscriptionStatus.PAUSED.canTransitionTo(SubscriptionStatus.PAST_DUE)).isFalse();

        assertThat(SubscriptionStatus.PAST_DUE.canTransitionTo(SubscriptionStatus.ACTIVE)).isTrue();
        assertThat(SubscriptionStatus.PAST_DUE.canTransitionTo(SubscriptionStatus.CANCELLED)).isTrue();
        assertThat(SubscriptionStatus.PAST_DUE.canTransitionTo(SubscriptionStatus.PAUSED)).isFalse();

        // Terminal states permit zero outgoing transitions
        for (SubscriptionStatus target : SubscriptionStatus.values()) {
            assertThat(SubscriptionStatus.CANCELLED.canTransitionTo(target)).isFalse();
            assertThat(SubscriptionStatus.EXPIRED.canTransitionTo(target)).isFalse();
        }
    }
}
