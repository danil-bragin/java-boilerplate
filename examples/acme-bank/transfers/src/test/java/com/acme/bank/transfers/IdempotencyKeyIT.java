package com.acme.bank.transfers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.bank.transfers.adapter.out.posting.AccountsPostingSyncClient;
import com.acme.bank.transfers.adapter.out.posting.SyncPostResult;
import com.acme.test.PostgresTestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Fix 2 (BANK-22): the transferId is DERIVED deterministically from the {@code Idempotency-Key}, and a
 * missing/blank key is rejected with 400. The edge {@code IdempotencyFilter} is DISABLED here
 * ({@code acme.web.idempotency.enabled=false}) to prove correctness rests on the anchor — NOT solely on
 * the in-memory filter cache: two same-key requests that BOTH reach transfers still produce ONE transfer
 * and ONE posting (the posting-PK anchor + the existing-row check dedup).
 */
@SpringBootTest(
        properties = {
            "spring.kafka.listener.auto-startup=false",
            "acme.web.idempotency.enabled=false",
            "acme.bank.fast-path.enabled=true",
            "acme.bank.fast-path.max-amount=1000.00"
        })
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfiguration.class)
class IdempotencyKeyIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    @MockitoBean
    AccountsPostingSyncClient sync;

    private static final String VALID =
            "{\"sourceAccountId\":\"a\",\"destinationAccountId\":\"b\",\"amount\":\"100.00\",\"asset\":\"USD\"}";

    @Test
    void keylessRequestIsRejectedWith400() throws Exception {
        mvc.perform(post("/v1/transfers")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void blankKeyIsRejectedWith400() throws Exception {
        mvc.perform(post("/v1/transfers")
                        .with(jwt())
                        .header("Idempotency-Key", "  ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    // Filter DISABLED: both same-key requests reach transfers. The derived transferId is identical, so the
    // existing-row check + the accounts anchor dedup → exactly ONE transfer, ONE posting.
    @Test
    void sameKeyReachingTransfersTwiceProducesOneTransferAndOnePosting() throws Exception {
        when(sync.post(any())).thenReturn(SyncPostResult.posted());
        long before = jdbc.queryForObject("SELECT count(*) FROM transfer", Long.class);

        String first = perform("same-key-xyz");
        String second = perform("same-key-xyz");

        JsonNode a = mapper.readTree(first);
        JsonNode b = mapper.readTree(second);
        assertThat(b.get("transferId").asText()).isEqualTo(a.get("transferId").asText());

        long after = jdbc.queryForObject("SELECT count(*) FROM transfer", Long.class);
        assertThat(after).isEqualTo(before + 1); // exactly ONE transfer despite two POSTs

        // The second POST resolved to the existing (COMPLETED) row — accounts is posted at most once.
        verify(sync, times(1)).post(any());
    }

    private String perform(String key) throws Exception {
        return mvc.perform(post("/v1/transfers")
                        .with(jwt())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
