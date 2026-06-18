package com.acme.bank.accounts.application;

import static org.assertj.core.api.Assertions.assertThat;

import an.awesome.pipelinr.Pipeline;
import com.acme.money.Assets;
import com.acme.money.Money;
import com.acme.test.PostgresTestcontainersConfiguration;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Import(PostgresTestcontainersConfiguration.class)
class OpenAccountIT {

    @Autowired
    Pipeline pipeline;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void opensAccountWithDoubleEntryOpeningDeposit() {
        OpenAccountResult result = pipeline.send(
                new OpenAccountCommand("req-open-1", "Ada Lovelace", Assets.USD, Money.of("100.00", Assets.USD)));

        // (a) an account row exists, OPEN, with a generated IBAN
        String status =
                jdbc.queryForObject("SELECT status FROM account WHERE id = ?", String.class, result.accountId());
        assertThat(status).isEqualTo("OPEN");
        String iban = jdbc.queryForObject("SELECT iban FROM account WHERE id = ?", String.class, result.accountId());
        assertThat(iban).isEqualTo(result.iban());
        assertThat(iban).isNotBlank();

        // (b) the new account's derived balance == 100.00 USD
        BigDecimal balance = jdbc.queryForObject(
                "SELECT coalesce(sum(amount),0) FROM ledger_entry WHERE account_id = ? AND asset = 'USD'",
                BigDecimal.class,
                result.accountId());
        assertThat(balance).isEqualByComparingTo("100.00");

        // (c) the opening posting balances to zero (equity decreased by 100.00)
        String openTransferId = "open-" + result.accountId();
        BigDecimal postingSum = jdbc.queryForObject(
                "SELECT coalesce(sum(amount),0) FROM ledger_entry WHERE transfer_id = ?",
                BigDecimal.class,
                openTransferId);
        assertThat(postingSum).isEqualByComparingTo("0");
        BigDecimal equity = jdbc.queryForObject(
                "SELECT coalesce(sum(amount),0) FROM ledger_entry WHERE account_id = 'bank-equity' AND transfer_id = ?",
                BigDecimal.class,
                openTransferId);
        assertThat(equity).isEqualByComparingTo("-100.00");
    }

    @Test
    void opensAccountWithoutDepositHasZeroBalanceAndNoPosting() {
        OpenAccountResult result =
                pipeline.send(new OpenAccountCommand("req-open-2", "Grace Hopper", Assets.USD, null));

        Long entries = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entry WHERE account_id = ?", Long.class, result.accountId());
        assertThat(entries).isEqualTo(0L);
        BigDecimal balance = jdbc.queryForObject(
                "SELECT coalesce(sum(amount),0) FROM ledger_entry WHERE account_id = ?",
                BigDecimal.class,
                result.accountId());
        assertThat(balance).isEqualByComparingTo("0");
    }

    @Test
    void sameRequestIdDoesNotDoubleOpen() {
        OpenAccountResult first = pipeline.send(
                new OpenAccountCommand("req-open-dup", "Alan Turing", Assets.USD, Money.of("50.00", Assets.USD)));
        OpenAccountResult second = pipeline.send(
                new OpenAccountCommand("req-open-dup", "Alan Turing", Assets.USD, Money.of("50.00", Assets.USD)));

        assertThat(second.accountId()).isEqualTo(first.accountId());
        Long accounts = jdbc.queryForObject("SELECT count(*) FROM account WHERE id = ?", Long.class, first.accountId());
        assertThat(accounts).isEqualTo(1L);
        BigDecimal balance = jdbc.queryForObject(
                "SELECT coalesce(sum(amount),0) FROM ledger_entry WHERE account_id = ? AND asset = 'USD'",
                BigDecimal.class,
                first.accountId());
        assertThat(balance).isEqualByComparingTo("50.00"); // not 100.00 — opened once
    }
}
