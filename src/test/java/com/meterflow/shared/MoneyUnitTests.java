package com.meterflow.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MoneyUnitTests {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void supportsBdtAndUsdArithmeticWithoutImplicitIntermediateRounding() {
        Money bdtRate = Money.bdt("0.00125");
        Money unrounded = bdtRate.multiply(new BigDecimal("3.3333"));

        // Exact unrounded intermediate result: 0.00125 * 3.3333 = 0.004166625 (scale 9)
        assertThat(unrounded.amount()).isEqualByComparingTo("0.004166625");
        assertThat(unrounded.amount().scale()).isEqualTo(9);
        assertThat(unrounded.currency()).isEqualTo(SupportedCurrency.BDT);

        // Explicit 3-tier rounding policy (ADR-010)
        assertThat(unrounded.roundToIntermediate().amount()).isEqualByComparingTo("0.00416663");
        assertThat(unrounded.roundToIntermediate().amount().scale()).isEqualTo(8);
        assertThat(unrounded.roundToPersisted().amount()).isEqualByComparingTo("0.0042");
        assertThat(unrounded.roundToPersisted().amount().scale()).isEqualTo(4);
        assertThat(unrounded.roundToPayable().amount()).isEqualByComparingTo("0.00");
        assertThat(unrounded.roundToPayable().amount().scale()).isEqualTo(2);

        Money usdSubtotal = Money.usd("100.255").add(Money.usd("49.745"));
        assertThat(usdSubtotal.amount()).isEqualByComparingTo("150.000");
        assertThat(usdSubtotal.subtract(Money.usd("25.004")).roundToPayable().amount())
                .isEqualByComparingTo("125.00");
    }

    @Test
    void enforcesLastPaisaForBdtAndLastCentForUsdPayableRounding() {
        assertThat(Money.bdt("10.125").roundToPayable().amount()).isEqualByComparingTo("10.13");
        assertThat(Money.bdt("10.1249").roundToPayable().amount()).isEqualByComparingTo("10.12");
        assertThat(Money.bdt("-10.125").roundToPayable().amount()).isEqualByComparingTo("-10.13");

        assertThat(Money.usd("99.995").roundToPayable().amount()).isEqualByComparingTo("100.00");
        assertThat(Money.usd("99.994").roundToPayable().amount()).isEqualByComparingTo("99.99");

        Money fromPaisa = Money.ofMinorUnits(123456L, SupportedCurrency.BDT);
        assertThat(fromPaisa.amount()).isEqualByComparingTo("1234.56");
        assertThat(fromPaisa.currency()).isEqualTo(SupportedCurrency.BDT);

        Money fromCents = Money.ofMinorUnits(-5099L, SupportedCurrency.USD);
        assertThat(fromCents.amount()).isEqualByComparingTo("-50.99");
        assertThat(fromCents.isNegative()).isTrue();
    }

    @Test
    void supportsNegationAbsMultiplicationDivisionAndComparisons() {
        Money base = Money.bdt("250.00");
        Money negated = base.negate();
        assertThat(negated.isNegative()).isTrue();
        assertThat(negated.amount()).isEqualByComparingTo("-250.00");
        assertThat(negated.abs()).isEqualTo(base);

        Money zeroNegated = Money.bdt("-0.00").negate();
        assertThat(zeroNegated.isZero()).isTrue();
        assertThat(zeroNegated.amount().signum()).isZero();

        Money multiplied = base.multiply(3L);
        assertThat(multiplied).isEqualTo(Money.bdt("750"));

        Money divided = Money.usd("100").divide(new BigDecimal("3"), Money.INTERMEDIATE_SCALE, RoundingMode.HALF_UP);
        assertThat(divided.amount()).isEqualByComparingTo("33.33333333");
        assertThat(divided.roundToPayable().amount()).isEqualByComparingTo("33.33");

        Money low = Money.usd("10.00");
        Money high = Money.usd("20.00");
        assertThat(low.compareTo(high)).isNegative();
        assertThat(high.isGreaterThan(low)).isTrue();
        assertThat(high.isGreaterThanOrEqual(Money.usd("20.0000"))).isTrue();
        assertThat(low.isLessThan(high)).isTrue();
        assertThat(low.isLessThanOrEqual(Money.usd("10"))).isTrue();
        assertThat(low.min(high)).isEqualTo(low);
        assertThat(low.max(high)).isEqualTo(high);
    }

    @Test
    void enforcesScaleInsensitiveNumericalEqualityAndConsistentHashCode() {
        Money twoDecimals = Money.bdt("100.00");
        Money fourDecimals = Money.bdt("100.0000");
        Money scientific = Money.of(new BigDecimal("1E+2"), SupportedCurrency.BDT);
        Money differentCurrency = Money.usd("100.00");

        assertThat(twoDecimals).isEqualTo(fourDecimals);
        assertThat(twoDecimals).isEqualTo(scientific);
        assertThat(twoDecimals.hashCode()).isEqualTo(fourDecimals.hashCode());
        assertThat(twoDecimals.hashCode()).isEqualTo(scientific.hashCode());
        assertThat(twoDecimals).isNotEqualTo(differentCurrency);

        Money zeroA = Money.zero("bdt");
        Money zeroB = Money.bdt("0.0000");
        Money zeroC = Money.bdt("-0.00");
        assertThat(zeroA).isEqualTo(zeroB).isEqualTo(zeroC);
        assertThat(zeroA.hashCode()).isEqualTo(zeroB.hashCode()).isEqualTo(zeroC.hashCode());
    }

    @Test
    void rejectsCurrencyMismatchesInvalidCurrenciesNullsAndUnsafeArithmetic() {
        Money bdt = Money.bdt("50.00");
        Money usd = Money.usd("50.00");

        assertThatThrownBy(() -> bdt.add(usd)).isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> bdt.subtract(usd)).isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> bdt.compareTo(usd)).isInstanceOf(CurrencyMismatchException.class);

        assertThatThrownBy(() -> Money.of(BigDecimal.TEN, "EUR"))
                .isInstanceOf(InvalidCurrencyException.class)
                .hasMessageContaining("Unsupported currency");
        assertThatThrownBy(() -> Money.of(BigDecimal.TEN, (String) null))
                .isInstanceOf(InvalidCurrencyException.class);
        assertThatThrownBy(() -> Money.of(BigDecimal.TEN, "   "))
                .isInstanceOf(InvalidCurrencyException.class);
        assertThatThrownBy(() -> Money.of(BigDecimal.TEN, (SupportedCurrency) null))
                .isInstanceOf(InvalidCurrencyException.class);

        assertThatThrownBy(() -> Money.of((BigDecimal) null, SupportedCurrency.BDT))
                .isInstanceOf(InvalidMoneyException.class);
        assertThatThrownBy(() -> Money.bdt((String) null))
                .isInstanceOf(InvalidMoneyException.class);
        assertThatThrownBy(() -> Money.bdt("not-a-number"))
                .isInstanceOf(InvalidMoneyException.class);
        assertThatThrownBy(() -> bdt.add(null))
                .isInstanceOf(InvalidMoneyException.class);
        assertThatThrownBy(() -> bdt.multiply(null))
                .isInstanceOf(InvalidMoneyException.class);
        assertThatThrownBy(() -> bdt.divide(BigDecimal.ZERO, 4, RoundingMode.HALF_UP))
                .isInstanceOf(InvalidMoneyException.class);
        assertThatThrownBy(() -> bdt.divide(new BigDecimal("0.0000"), 4, RoundingMode.HALF_UP))
                .isInstanceOf(InvalidMoneyException.class);
        assertThatThrownBy(() -> bdt.round(-1, RoundingMode.HALF_UP))
                .isInstanceOf(InvalidMoneyException.class);
        assertThatThrownBy(() -> bdt.round(2, null))
                .isInstanceOf(InvalidMoneyException.class);
    }

    @Test
    void handlesLargeBoundaryValuesAndSafePlainDecimalJsonSerialization() throws Exception {
        Money large = Money.bdt("999999999999999.9999");
        Money scientific = Money.of(new BigDecimal("1E+6"), SupportedCurrency.USD);

        String largeJson = jsonMapper.writeValueAsString(large);
        assertThat(largeJson).isEqualTo("{\"amount\":\"999999999999999.9999\",\"currency\":\"BDT\"}");

        String scientificJson = jsonMapper.writeValueAsString(scientific);
        assertThat(scientificJson).isEqualTo("{\"amount\":\"1000000\",\"currency\":\"USD\"}");
        assertThat(scientificJson).doesNotContain("E+");

        Money deserialized = jsonMapper.readValue(largeJson, Money.class);
        assertThat(deserialized).isEqualTo(large);
        assertThat(deserialized.amount()).isEqualByComparingTo("999999999999999.9999");
        assertThat(deserialized.currency()).isEqualTo(SupportedCurrency.BDT);

        assertThatThrownBy(() -> jsonMapper.readValue("{\"amount\":\"10.00\",\"currency\":\"GBP\"}", Money.class))
                .hasRootCauseInstanceOf(InvalidCurrencyException.class);
    }
}
