package com.acme.bank.contracts;

import com.acme.money.Assets;
import com.acme.money.Money;

/** Bridges the domain {@link Money} value type to the Avro wire contract (string amount + asset). */
public final class MoneyMapper {

    private MoneyMapper() {}

    public static com.acme.bank.contracts.avro.Money toAvro(Money money) {
        return com.acme.bank.contracts.avro.Money.newBuilder()
                .setAmount(money.toAmountString())
                .setAsset(money.asset().code())
                .build();
    }

    public static Money fromAvro(com.acme.bank.contracts.avro.Money avro) {
        return Money.of(avro.getAmount().toString(), Assets.of(avro.getAsset().toString()));
    }
}
