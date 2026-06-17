package com.acme.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class MoneyFormatSerdeTest {

    @Test
    void formatRendersAtAssetScale() {
        assertThat(Money.of("10", Assets.USD).format()).isEqualTo("10.00 USD");
        assertThat(Money.of("10", Assets.JPY).format()).isEqualTo("10 JPY");
    }

    @Test
    void toMinorReturnsSmallestUnits() {
        assertThat(Money.of("10.05", Assets.USD).toMinor()).isEqualTo(BigInteger.valueOf(1005));
        assertThat(Money.ofMinor(BigInteger.ONE, Assets.ETH).toMinor()).isEqualTo(BigInteger.ONE);
    }

    @Test
    void wireRoundTripViaStrings() {
        Money original = Money.of("1234.56", Assets.USD);
        // wire form: amount string + asset code
        String amount = original.toAmountString();
        String code = original.asset().code();
        Money parsed = Money.of(amount, Assets.of(code));
        assertThat(parsed).isEqualTo(original);
    }
}
