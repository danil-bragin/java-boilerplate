package com.acme.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

class MoneyDivisionTest {

    @Test
    void divideRoundsHalfEvenByDefault() {
        // 10.00 / 3 = 3.333... -> 3.33 at scale 2
        Money r = Money.of("10.00", Assets.USD).divide(new BigDecimal("3"), 2);
        assertThat(r).isEqualTo(Money.of("3.33", Assets.USD));
    }

    @Test
    void bankersRoundingRoundsHalfToEven() {
        // 2.125 / 1 at scale 2 -> 2.12 (half to even), 2.135 -> 2.14
        assertThat(Money.of("2.125", Assets.USD).divide(BigDecimal.ONE, 2)).isEqualTo(Money.of("2.12", Assets.USD));
        assertThat(Money.of("2.135", Assets.USD).divide(BigDecimal.ONE, 2)).isEqualTo(Money.of("2.14", Assets.USD));
    }

    @Test
    void explicitRoundingModeIsHonoured() {
        assertThat(Money.of("10.00", Assets.USD).divide(new BigDecimal("3"), 2, RoundingMode.UP))
                .isEqualTo(Money.of("3.34", Assets.USD));
    }
}
