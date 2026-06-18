package com.acme.bank.accounts.application;

import static org.assertj.core.api.Assertions.assertThat;

import an.awesome.pipelinr.Pipeline;
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
class PostTransferIT {

    @Autowired
    Pipeline pipeline;

    @Autowired
    JdbcTemplate jdbc;

    private void openAccount(String id) {
        openAccount(id, "USD");
    }

    private void openAccount(String id, String asset) {
        jdbc.update("INSERT INTO account(id, iban, status, asset) VALUES (?, ?, 'OPEN', ?)", id, "IBAN-" + id, asset);
    }

    private void seedBalance(String accountId, String amount) {
        java.math.BigDecimal bd = new java.math.BigDecimal(amount);
        jdbc.update(
                "INSERT INTO ledger_entry(id, transfer_id, account_id, amount, asset) "
                        + "VALUES (nextval('ledger_entry_seq'), ?, ?, ?, 'USD')",
                "seed-" + accountId,
                accountId,
                bd);
        jdbc.update(
                "INSERT INTO ledger_entry(id, transfer_id, account_id, amount, asset) "
                        + "VALUES (nextval('ledger_entry_seq'), ?, 'funding', ?, 'USD')",
                "seed-" + accountId,
                bd.negate());
    }

    @Test
    void postsABalancedTransferAndDerivesBalances() {
        openAccount("src");
        openAccount("dst");
        seedBalance("src", "500.00");

        PostTransferResult result =
                pipeline.send(new PostTransferCommand("t-1", "src", "dst", Money.of("120.00", Assets.USD)));

        assertThat(result.posted()).isTrue();
        Long entries = jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE transfer_id = 't-1'", Long.class);
        assertThat(entries).isEqualTo(2L);
        java.math.BigDecimal sum = jdbc.queryForObject(
                "SELECT sum(amount) FROM ledger_entry WHERE transfer_id = 't-1'", java.math.BigDecimal.class);
        assertThat(sum).isEqualByComparingTo("0");
    }

    @Test
    void rejectsInsufficientFundsWithoutMovingMoney() {
        openAccount("poor");
        openAccount("rich");
        seedBalance("poor", "10.00");

        long before = jdbc.queryForObject("SELECT count(*) FROM ledger_entry", Long.class);
        PostTransferResult result =
                pipeline.send(new PostTransferCommand("t-2", "poor", "rich", Money.of("100.00", Assets.USD)));

        assertThat(result.posted()).isFalse();
        assertThat(result.reason()).isEqualTo("INSUFFICIENT_FUNDS");
        long after = jdbc.queryForObject("SELECT count(*) FROM ledger_entry", Long.class);
        assertThat(after).isEqualTo(before);
    }

    @Test
    void rejectsPostingFromNonOperationalAccountWithoutMovingMoney() {
        jdbc.update(
                "INSERT INTO account(id, iban, status, asset) VALUES (?, ?, 'FROZEN', 'USD')", "frozen", "IBAN-frozen");
        openAccount("dst-op");
        seedBalance("frozen", "500.00");

        long before = jdbc.queryForObject("SELECT count(*) FROM ledger_entry", Long.class);
        PostTransferResult result =
                pipeline.send(new PostTransferCommand("t-frozen", "frozen", "dst-op", Money.of("50.00", Assets.USD)));

        assertThat(result.posted()).isFalse();
        assertThat(result.reason()).isEqualTo("ACCOUNT_NOT_OPERATIONAL");
        long after = jdbc.queryForObject("SELECT count(*) FROM ledger_entry", Long.class);
        assertThat(after).isEqualTo(before);
    }

    @Test
    void postingWithMismatchedAssetIsRejected() {
        // dst is a EUR account; transferring USD into it must be rejected, not silently summed.
        openAccount("src-usd", "USD");
        openAccount("dst-eur", "EUR");
        seedBalance("src-usd", "500.00");

        long before = jdbc.queryForObject("SELECT count(*) FROM ledger_entry", Long.class);
        PostTransferResult result = pipeline.send(
                new PostTransferCommand("t-mismatch", "src-usd", "dst-eur", Money.of("50.00", Assets.USD)));

        assertThat(result.posted()).isFalse();
        assertThat(result.reason()).isEqualTo("ACCOUNT_ASSET_MISMATCH");
        long after = jdbc.queryForObject("SELECT count(*) FROM ledger_entry", Long.class);
        assertThat(after).isEqualTo(before);
    }

    @Test
    void postingIsIdempotentByTransferId() {
        openAccount("s2");
        openAccount("d2");
        seedBalance("s2", "500.00");

        pipeline.send(new PostTransferCommand("t-3", "s2", "d2", Money.of("50.00", Assets.USD)));
        pipeline.send(new PostTransferCommand("t-3", "s2", "d2", Money.of("50.00", Assets.USD)));

        Long entries = jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE transfer_id = 't-3'", Long.class);
        assertThat(entries).isEqualTo(2L);
    }

    @Test
    void concurrentSameTransferDoesNotDoublePost() throws Exception {
        openAccount("sc");
        openAccount("dc");
        seedBalance("sc", "500.00");

        var command = new PostTransferCommand("t-conc", "sc", "dc", Money.of("50.00", Assets.USD));
        int threads = 8;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var start = new java.util.concurrent.CountDownLatch(1);
        var done = new java.util.concurrent.CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    pipeline.send(command); // losers throw a constraint violation; winner commits
                } catch (Exception ignored) {
                    // expected for the racing losers
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        pool.shutdown();

        // The PK anchor guarantees the transfer is posted exactly once: 2 entries, never 4+.
        Long entries =
                jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE transfer_id = 't-conc'", Long.class);
        assertThat(entries).isEqualTo(2L);
        Long postings = jdbc.queryForObject("SELECT count(*) FROM posting WHERE transfer_id = 't-conc'", Long.class);
        assertThat(postings).isEqualTo(1L);
    }
}
