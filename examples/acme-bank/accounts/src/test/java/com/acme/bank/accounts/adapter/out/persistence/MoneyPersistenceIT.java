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
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Import(PostgresTestcontainersConfiguration.class)
class MoneyPersistenceIT {

    @Autowired
    Ledger ledger;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void savesAndDerivesBalanceWithExactMoney() {
        AccountId a = new AccountId("acc-a");
        AccountId b = new AccountId("acc-b");
        // The single-asset invariant resolves each leg's asset from its account row, so seed both USD.
        jdbc.update("INSERT INTO account(id, iban, status, asset) VALUES ('acc-a', 'IBAN-acc-a', 'OPEN', 'USD')");
        jdbc.update("INSERT INTO account(id, iban, status, asset) VALUES ('acc-b', 'IBAN-acc-b', 'OPEN', 'USD')");
        ledger.save(Posting.transfer("t-money", a, b, Money.of("100.05", Assets.USD)));

        assertThat(ledger.balance(a, Assets.USD)).isEqualTo(Money.of("-100.05", Assets.USD));
        assertThat(ledger.balance(b, Assets.USD)).isEqualTo(Money.of("100.05", Assets.USD));
    }
}
