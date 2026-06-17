package com.acme.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyCompareEqualityTest {

    @Test
    void equalityIsScaleInsensitive() {
        assertThat(Money.of("10.0", Assets.USD)).isEqualTo(Money.of("10.00", Assets.USD));
        assertThat(Money.of("10.0", Assets.USD)).hasSameHashCodeAs(Money.of("10.00", Assets.USD));
    }

    @Test
    void differentAssetsAreNotEqual() {
        assertThat(Money.of("10", Assets.USD)).isNotEqualTo(Money.of("10", Assets.EUR));
    }

    @Test
    void comparison() {
        Money low = Money.of("1.00", Assets.USD);
        Money high = Money.of("2.00", Assets.USD);
        assertThat(low).isLessThan(high);
        assertThat(low.min(high)).isEqualTo(low);
        assertThat(low.max(high)).isEqualTo(high);
    }

    @Test
    void comparingDifferentAssetsThrows() {
        assertThatThrownBy(() -> Money.of("1", Assets.USD).compareTo(Money.of("1", Assets.EUR)))
                .isInstanceOf(CurrencyMismatchException.class);
    }
}
