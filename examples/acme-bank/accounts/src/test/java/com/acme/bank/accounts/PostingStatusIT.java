package com.acme.bank.accounts;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.test.PostgresTestcontainersConfiguration;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the internal posting-status query — the money source of truth used by the transfers
 * reconciler. The endpoint is network-internal (no bearer required on {@code /internal/**}).
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfiguration.class)
class PostingStatusIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void postedTransferReportsPostedTrue() throws Exception {
        String transferId = "t1-" + System.nanoTime();
        // The money truth is "a ledger entry carries this transfer_id" — seed one directly.
        jdbc.update(
                "INSERT INTO ledger_entry(id, transfer_id, account_id, amount, asset) "
                        + "VALUES (nextval('ledger_entry_seq'), ?, 'acc-src', ?, 'USD')",
                transferId,
                new BigDecimal("-100.00"));

        // No bearer: /internal/** is permitted (network-segmented, not exposed at the gateway edge).
        mvc.perform(get("/internal/postings/{transferId}", transferId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").value(transferId))
                .andExpect(jsonPath("$.posted").value(true));
    }

    @Test
    void unpostedTransferReportsPostedFalse() throws Exception {
        // 200 with a boolean (NOT 404) so the client can distinguish "definitely not posted"
        // from a transport error (which the reconciler treats as "skip this round").
        mvc.perform(get("/internal/postings/{transferId}", "never-posted-" + System.nanoTime()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posted").value(false));
    }
}
