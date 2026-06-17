package com.acme.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.money.Assets;
import com.acme.money.Money;
import org.junit.jupiter.api.Test;

class MoneyAmountTest {

    @Test
    void roundTripsMoney() {
        Money money = Money.of("1234.56", Assets.USD);
        MoneyAmount embeddable = MoneyAmount.from(money);
        assertThat(embeddable.getAmount()).isEqualByComparingTo("1234.56");
        assertThat(embeddable.getAsset()).isEqualTo("USD");
        assertThat(embeddable.toMoney(Assets::of)).isEqualTo(money);
    }
}
