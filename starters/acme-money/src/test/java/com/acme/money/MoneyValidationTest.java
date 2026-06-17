package com.acme.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyValidationTest {

    @Test
    void rejectsTooManySignificantDigits() {
        String huge = "1".repeat(1001);
        assertThatThrownBy(() -> Money.of(huge, Assets.USD)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsExponentOutOfBounds() {
        // scale > 256 (very long fractional part) is rejected
        String deepFraction = "0." + "0".repeat(257) + "1";
        assertThatThrownBy(() -> Money.of(deepFraction, Assets.USD)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonNumeric() {
        assertThatThrownBy(() -> Money.of("not-a-number", Assets.USD)).isInstanceOf(NumberFormatException.class);
    }

    @Test
    void acceptsRealisticCryptoPrecision() {
        // 18-dp ETH is well within bounds
        assertThat(Money.of("1.000000000000000001", Assets.ETH).toMinor()).isNotNull();
    }
}
