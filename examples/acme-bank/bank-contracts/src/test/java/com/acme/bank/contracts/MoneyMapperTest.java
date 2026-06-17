package com.acme.bank.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.money.Assets;
import com.acme.money.Money;
import org.junit.jupiter.api.Test;

class MoneyMapperTest {

    @Test
    void roundTripsThroughAvro() {
        Money original = Money.of("1234.56", Assets.USD);

        com.acme.bank.contracts.avro.Money avro = MoneyMapper.toAvro(original);
        assertThat(avro.getAmount().toString()).isEqualTo("1234.56");
        assertThat(avro.getAsset().toString()).isEqualTo("USD");

        Money back = MoneyMapper.fromAvro(avro);
        assertThat(back).isEqualTo(original);
    }

    @Test
    void preservesCryptoPrecision() {
        Money wei = Money.of("0.000000000000000001", Assets.ETH);
        assertThat(MoneyMapper.fromAvro(MoneyMapper.toAvro(wei))).isEqualTo(wei);
    }
}
