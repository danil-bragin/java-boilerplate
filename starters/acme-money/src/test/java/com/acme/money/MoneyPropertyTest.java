package com.acme.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

class MoneyPropertyTest {

    @Property
    void allocationAlwaysConservesTheTotal(
            @ForAll @LongRange(min = -1_000_000_000L, max = 1_000_000_000L) long cents,
            @ForAll @IntRange(min = 1, max = 20) int parts) {
        Money total = Money.ofMinor(java.math.BigInteger.valueOf(cents), Assets.USD);
        List<Money> allocated = total.split(parts);
        Money sum = allocated.stream().reduce(Money.zero(Assets.USD), Money::add);
        assertThat(sum).isEqualTo(total);
    }

    @Property
    void amountStringRoundTrips(@ForAll @LongRange(min = -1_000_000_000L, max = 1_000_000_000L) long cents) {
        Money money = Money.ofMinor(java.math.BigInteger.valueOf(cents), Assets.USD);
        Money parsed = Money.of(money.toAmountString(), Assets.USD);
        assertThat(parsed).isEqualTo(money);
    }
}
