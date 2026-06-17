package com.acme.bank.accounts.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.bank.accounts.domain.AccountId;
import com.acme.bank.accounts.domain.Ledger;
import com.acme.bank.accounts.domain.Posting;
import com.acme.money.Assets;
import com.acme.money.Money;
import com.acme.test.PostgresTestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(PostgresTestcontainersConfiguration.class)
class MoneyPersistenceIT {

    @Autowired
    Ledger ledger;

    @Test
    void savesAndDerivesBalanceWithExactMoney() {
        AccountId a = new AccountId("acc-a");
        AccountId b = new AccountId("acc-b");
        ledger.save(Posting.transfer("t-money", a, b, Money.of("100.05", Assets.USD)));

        assertThat(ledger.balance(a, Assets.USD)).isEqualTo(Money.of("-100.05", Assets.USD));
        assertThat(ledger.balance(b, Assets.USD)).isEqualTo(Money.of("100.05", Assets.USD));
    }
}
