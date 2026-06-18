package com.acme.bank.accounts.application;

import static org.assertj.core.api.Assertions.assertThat;

import an.awesome.pipelinr.Pipeline;
import com.acme.money.Assets;
import com.acme.money.Money;
import com.acme.test.PostgresTestcontainersConfiguration;
import java.math.BigDecimal;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Import(PostgresTestcontainersConfiguration.class)
class ConcurrentDebitIT {

    @Autowired
    Pipeline pipeline;

    @Autowired
    JdbcTemplate jdbc;

    private void openAccount(String id) {
        jdbc.update("INSERT INTO account(id, iban, status, asset) VALUES (?, ?, 'OPEN', 'USD')", id, "IBAN-" + id);
    }

    private void seedBalance(String accountId, String amount) {
        BigDecimal bd = new BigDecimal(amount);
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

    private BigDecimal balanceOf(String accountId) {
        return jdbc.queryForObject(
                "SELECT coalesce(sum(amount),0) FROM ledger_entry WHERE account_id = ? AND asset = 'USD'",
                BigDecimal.class,
                accountId);
    }

    @Test
    void concurrentDistinctTransfersCannotOverdrawSource() throws Exception {
        // Source funded with exactly 100.00 USD; 8 racing 80.00 debits — only one can be afforded.
        openAccount("hot-src");
        openAccount("hot-dst");
        seedBalance("hot-src", "100.00");

        int threads = 8;
        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var results = new CopyOnWriteArrayList<PostTransferResult>();
        for (int i = 0; i < threads; i++) {
            String transferId = "concurrent-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    results.add(pipeline.send(
                            new PostTransferCommand(transferId, "hot-src", "hot-dst", Money.of("80.00", Assets.USD))));
                } catch (Exception e) {
                    // A racing loser may surface a constraint violation rather than a clean rejection;
                    // either way it must NOT have moved money — asserted below by the ledger state.
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        pool.shutdown();

        // Exactly one debit posted; the rest cleanly rejected for INSUFFICIENT_FUNDS.
        long posted = results.stream().filter(PostTransferResult::posted).count();
        assertThat(posted).isEqualTo(1L);
        assertThat(results.stream().filter(r -> !r.posted()))
                .allSatisfy(r -> assertThat(r.reason()).isEqualTo("INSUFFICIENT_FUNDS"));

        // Source derived balance is exactly 20.00 and never negative.
        BigDecimal sourceBalance = balanceOf("hot-src");
        assertThat(sourceBalance).isEqualByComparingTo("20.00");
        assertThat(sourceBalance).isGreaterThanOrEqualTo(BigDecimal.ZERO);

        // Only the single successful posting was written: 2 ledger entries (both legs) and 1 posting.
        Long entries = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entry WHERE transfer_id LIKE 'concurrent-%'", Long.class);
        assertThat(entries).isEqualTo(2L);
        Long postings =
                jdbc.queryForObject("SELECT count(*) FROM posting WHERE transfer_id LIKE 'concurrent-%'", Long.class);
        assertThat(postings).isEqualTo(1L);
    }
}
