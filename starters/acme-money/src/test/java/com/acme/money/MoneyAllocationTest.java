package com.acme.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class MoneyAllocationTest {

    @Test
    void allocateConservesEveryMinorUnit() {
        // 0.05 USD split 3 ways -> 0.02, 0.02, 0.01 (sum == 0.05, no penny lost)
        List<Money> parts = Money.of("0.05", Assets.USD).split(3);
        assertThat(parts)
                .containsExactly(
                        Money.of("0.02", Assets.USD), Money.of("0.02", Assets.USD), Money.of("0.01", Assets.USD));
        assertThat(parts.stream().reduce(Money.zero(Assets.USD), Money::add)).isEqualTo(Money.of("0.05", Assets.USD));
    }

    @Test
    void allocateByRatios() {
        // 1.00 USD allocated 1:1:2 -> 0.25, 0.25, 0.50
        List<Money> parts = Money.of("1.00", Assets.USD).allocate(1, 1, 2);
        assertThat(parts)
                .containsExactly(
                        Money.of("0.25", Assets.USD), Money.of("0.25", Assets.USD), Money.of("0.50", Assets.USD));
    }

    @Test
    void allocateIsSignAware() {
        List<Money> parts = Money.of("-0.05", Assets.USD).split(3);
        assertThat(parts.stream().reduce(Money.zero(Assets.USD), Money::add)).isEqualTo(Money.of("-0.05", Assets.USD));
    }

    @Test
    void allocateRejectsEmptyOrZeroRatios() {
        assertThatThrownBy(() -> Money.of("1.00", Assets.USD).allocate()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of("1.00", Assets.USD).allocate(0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
