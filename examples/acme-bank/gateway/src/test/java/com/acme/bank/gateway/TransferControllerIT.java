package com.acme.bank.gateway;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.bank.gateway.client.TransfersRestClient;
import com.acme.test.PostgresTestcontainersConfiguration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfiguration.class, TransferControllerIT.FakeDownstream.class})
class TransferControllerIT {

    @TestConfiguration
    static class FakeDownstream {
        @Bean
        @Primary
        TransfersRestClient fakeTransfersRestClient() {
            return (request, idempotencyKey) -> "downstream-transfer-id";
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private static final String VALID =
            "{\"sourceAccountId\":\"a\",\"destinationAccountId\":\"b\",\"amount\":{\"value\":\"100.00\",\"asset\":\"USD\"}}";

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM transfer_view");
    }

    @Test
    void createTransferForwardsAndReturns202() throws Exception {
        mvc.perform(post("/v1/transfers")
                        .with(jwt())
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.transferId").value("downstream-transfer-id"))
                .andExpect(jsonPath("$.status").value("REQUESTED"));
    }

    @Test
    void unauthenticatedIsRejected() throws Exception {
        mvc.perform(post("/v1/transfers")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getTransferReturnsView() throws Exception {
        seedView("seed-1", "COMPLETED");
        mvc.perform(get("/v1/transfers/{id}", "seed-1").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").value("seed-1"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.amount.value").value("100.00"))
                .andExpect(jsonPath("$.amount.asset").value("USD"));
    }

    @Test
    void getMissingTransferReturns404ProblemJson() throws Exception {
        mvc.perform(get("/v1/transfers/{id}", "does-not-exist").with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
    }

    @Test
    void repeatedIdempotencyKeyReplaysTheSame202Body() throws Exception {
        String first = mvc.perform(post("/v1/transfers")
                        .with(jwt())
                        .header("Idempotency-Key", "idem-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // A retry with the SAME key replays the stored 2xx response byte-for-byte (idempotency filter).
        String second = mvc.perform(post("/v1/transfers")
                        .with(jwt())
                        .header("Idempotency-Key", "idem-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.assertj.core.api.Assertions.assertThat(second).isEqualTo(first);
    }

    @Test
    void malformedBodyMissingAmountReturns400ProblemJson() throws Exception {
        String missingAmount = "{\"sourceAccountId\":\"a\",\"destinationAccountId\":\"b\"}";
        mvc.perform(post("/v1/transfers")
                        .with(jwt())
                        .header("Idempotency-Key", "idem-bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingAmount))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.status").value(400));
    }

    private void seedView(String id, String status) {
        Instant now = Instant.now();
        jdbc.update(
                "INSERT INTO transfer_view (transfer_id, status, status_rank, amount_value, amount_asset, "
                        + "source_account_id, destination_account_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                status,
                4,
                new java.math.BigDecimal("100.00"),
                "USD",
                "a",
                "b",
                java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now));
    }
}
