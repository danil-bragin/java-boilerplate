package com.acme.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyArithmeticTest {

    @Test
    void addAndSubtractAreExact() {
        Money a = Money.of("10.05", Assets.USD);
        Money b = Money.of("0.95", Assets.USD);
        assertThat(a.add(b)).isEqualTo(Money.of("11.00", Assets.USD));
        assertThat(a.subtract(b)).isEqualTo(Money.of("9.10", Assets.USD));
    }

    @Test
    void multiplyGrowsScaleExactly() {
        Money price = Money.of("10.00", Assets.USD);
        Money taxed = price.multiply(new BigDecimal("1.015"));
        assertThat(taxed).isEqualTo(Money.of("10.15", Assets.USD)); // 10.00 * 1.015 = 10.150
    }

    @Test
    void negateAndAbs() {
        Money debit = Money.of("-5.00", Assets.USD);
        assertThat(debit.negate()).isEqualTo(Money.of("5.00", Assets.USD));
        assertThat(debit.abs()).isEqualTo(Money.of("5.00", Assets.USD));
    }

    @Test
    void mixingAssetsThrows() {
        assertThatThrownBy(() -> Money.of("1", Assets.USD).add(Money.of("1", Assets.EUR)))
                .isInstanceOf(CurrencyMismatchException.class);
    }
}
