package com.acme.bank.transfers;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.test.PostgresTestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfiguration.class)
class TransferQueryIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM transfer");
        insert("t-A", "acc-X", "acc-Y", "COMPLETED", null);
        insert("t-B", "acc-Z", "acc-X", "FAILED", "INSUFFICIENT_FUNDS");
    }

    private void insert(String id, String src, String dst, String status, String failureReason) {
        jdbc.update(
                "INSERT INTO transfer(id, source_account_id, destination_account_id, amount, asset, requested_by, status, failure_reason) "
                        + "VALUES (?, ?, ?, ?, 'USD', 'api', ?, ?)",
                id,
                src,
                dst,
                new java.math.BigDecimal("100.00"),
                status,
                failureReason);
    }

    @Test
    void unauthenticatedIsRejected() throws Exception {
        mvc.perform(get("/v1/transfers")).andExpect(status().isUnauthorized());
    }

    @Test
    void listsByAccountIncludingSourceOrDestination() throws Exception {
        // acc-X is the source of t-A and the destination of t-B -> both returned
        mvc.perform(get("/v1/transfers").param("accountId", "acc-X").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transfers.length()").value(2));

        // acc-Z is only t-B's source
        mvc.perform(get("/v1/transfers").param("accountId", "acc-Z").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transfers.length()").value(1))
                .andExpect(jsonPath("$.transfers[0].transferId").value("t-B"));
    }

    @Test
    void filtersByStatus() throws Exception {
        mvc.perform(get("/v1/transfers").param("status", "COMPLETED").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transfers.length()").value(1))
                .andExpect(jsonPath("$.transfers[0].transferId").value("t-A"));
    }

    @Test
    void getsOneFullView() throws Exception {
        mvc.perform(get("/v1/transfers/{id}", "t-B").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").value("t-B"))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.amount").value("100.00"))
                .andExpect(jsonPath("$.sourceAccountId").value("acc-Z"))
                .andExpect(jsonPath("$.destinationAccountId").value("acc-X"))
                .andExpect(jsonPath("$.failureReason").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void unknownIdIs404() throws Exception {
        mvc.perform(get("/v1/transfers/{id}", "nope").with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
    }

    @Test
    void listClampsHugePageSize() throws Exception {
        // A hostile size must be clamped to the 200 cap (no giant PageRequest).
        mvc.perform(get("/v1/transfers").param("size", "100000").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(200));
    }

    @Test
    void honoursPaging() throws Exception {
        mvc.perform(get("/v1/transfers")
                        .param("accountId", "acc-X")
                        .param("page", "0")
                        .param("size", "1")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transfers.length()").value(1));
    }
}
