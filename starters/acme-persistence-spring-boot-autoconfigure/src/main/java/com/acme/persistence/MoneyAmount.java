package com.acme.persistence;

import com.acme.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;

/** JPA embeddable mapping a {@link Money} to two columns: {@code NUMERIC amount} + {@code VARCHAR asset}. */
@Embeddable
public class MoneyAmount {

    @Column(name = "amount", nullable = false, precision = 38, scale = 18)
    private BigDecimal amount;

    @Column(name = "asset", nullable = false, length = 16)
    private String asset;

    protected MoneyAmount() {
        // for JPA
    }

    private MoneyAmount(BigDecimal amount, String asset) {
        this.amount = amount;
        this.asset = asset;
    }

    public static MoneyAmount from(Money money) {
        return new MoneyAmount(
                new BigDecimal(money.toAmountString()), money.asset().code());
    }

    public Money toMoney(AssetLookup assets) {
        return Money.of(amount.toPlainString(), assets.resolve(asset));
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getAsset() {
        return asset;
    }
}
