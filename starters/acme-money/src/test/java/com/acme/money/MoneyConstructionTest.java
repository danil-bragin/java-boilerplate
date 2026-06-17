package com.acme.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class MoneyConstructionTest {

    @Test
    void ofStringParsesExactDecimal() {
        Money m = Money.of("10.05", Assets.USD);
        assertThat(m.asset()).isEqualTo(Assets.USD);
        assertThat(m.toAmountString()).isEqualTo("10.05");
    }

    @Test
    void ofMinorInterpretsSmallestUnits() {
        // 1005 cents = 10.05 USD
        Money m = Money.ofMinor(BigInteger.valueOf(1005), Assets.USD);
        assertThat(m.toAmountString()).isEqualTo("10.05");
        // 1 wei = 0.000000000000000001 ETH (18 dp)
        Money wei = Money.ofMinor(BigInteger.ONE, Assets.ETH);
        assertThat(wei.toAmountString()).isEqualTo("0.000000000000000001");
    }

    @Test
    void ofMajorTakesWholeUnits() {
        assertThat(Money.ofMajor(42, Assets.USD).toAmountString()).isEqualTo("42");
    }

    @Test
    void zeroIsZero() {
        assertThat(Money.zero(Assets.USD).isZero()).isTrue();
    }

    @Test
    void rejectsNullAsset() {
        assertThatThrownBy(() -> Money.of("1", null)).isInstanceOf(NullPointerException.class);
    }
}
