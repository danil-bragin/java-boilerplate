package com.acme.bank.accounts;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.test.PostgresTestcontainersConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class AccountApiIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    private static final String OPEN_100 =
            "{\"ownerName\":\"Ada\",\"asset\":\"USD\",\"initialDeposit\":{\"value\":\"100.00\",\"asset\":\"USD\"}}";

    private String openAccount() throws Exception {
        String body = mvc.perform(post("/v1/accounts")
                        .with(jwt())
                        .header("Idempotency-Key", "open-" + System.nanoTime())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OPEN_100))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountId").exists())
                .andExpect(jsonPath("$.iban").exists())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return mapper.readTree(body).get("accountId").asText();
    }

    @Test
    void unauthenticatedIsRejected() throws Exception {
        mvc.perform(post("/v1/accounts").contentType(MediaType.APPLICATION_JSON).content(OPEN_100))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void opensAccount() throws Exception {
        openAccount();
    }

    @Test
    void getsAccountView() throws Exception {
        String id = openAccount();
        mvc.perform(get("/v1/accounts/{id}", id).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(id))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.iban").exists());
    }

    @Test
    void getsDerivedBalance() throws Exception {
        String id = openAccount();
        mvc.perform(get("/v1/accounts/{id}/balance", id).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("100.00"))
                .andExpect(jsonPath("$.asset").value("USD"));
    }

    @Test
    void getsStatementWithRunningBalance() throws Exception {
        String id = openAccount();
        mvc.perform(get("/v1/accounts/{id}/statement", id).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines").isArray())
                .andExpect(jsonPath("$.lines[0].signedAmount").value("100.00"))
                .andExpect(jsonPath("$.lines[0].runningBalance").value("100.00"));
    }

    @Test
    void balanceOfNoDepositAccountUsesAccountAsset() throws Exception {
        // Open an EUR account with NO opening deposit (no ledger entries to derive an asset from).
        String openEur = "{\"ownerName\":\"Marie\",\"asset\":\"EUR\"}";
        String body = mvc.perform(post("/v1/accounts")
                        .with(jwt())
                        .header("Idempotency-Key", "open-eur-" + System.nanoTime())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(openEur))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = mapper.readTree(body).get("accountId").asText();

        // Balance must be 0 in the account's real currency (EUR), not the hardcoded-USD fallback.
        mvc.perform(get("/v1/accounts/{id}/balance", id).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("0.00"))
                .andExpect(jsonPath("$.asset").value("EUR"));
    }

    @Test
    void statementRunningBalanceIsCorrectAcrossPages() throws Exception {
        // 3 entries on one account that all collide on the SAME posted_at — the realistic case, since
        // entries of one posting share a single Instant.now(). True cumulative balance is 60.00.
        String acc = "stmt-" + System.nanoTime();
        jdbc.update("INSERT INTO account(id, iban, status, asset) VALUES (?, ?, 'OPEN', 'USD')", acc, "IBAN-" + acc);
        for (int i = 1; i <= 3; i++) {
            jdbc.update(
                    "INSERT INTO ledger_entry(id, transfer_id, account_id, amount, asset, posted_at) "
                            + "VALUES (nextval('ledger_entry_seq'), ?, ?, ?, 'USD', "
                            + "TIMESTAMP WITH TIME ZONE '2020-01-01 00:00:00+00')",
                    "stmt-tx-" + i,
                    acc,
                    new java.math.BigDecimal("20.00"));
        }

        // Page 0 (size 2): running 20.00, 40.00
        mvc.perform(get("/v1/accounts/{id}/statement", acc)
                        .param("page", "0")
                        .param("size", "2")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].runningBalance").value("20.00"))
                .andExpect(jsonPath("$.lines[1].runningBalance").value("40.00"));

        // Page 1 (size 2): the running balance must CONTINUE from page 0, i.e. 60.00 — not reset to
        // 20.00 because the same-timestamp prior page was dropped by a timestamp-only seed.
        mvc.perform(get("/v1/accounts/{id}/statement", acc)
                        .param("page", "1")
                        .param("size", "2")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].runningBalance").value("60.00"));
    }

    @Test
    void freezesAccount() throws Exception {
        String id = openAccount();
        mvc.perform(post("/v1/accounts/{id}/freeze", id).with(jwt())).andExpect(status().isOk());
        mvc.perform(get("/v1/accounts/{id}", id).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));
    }

    @Test
    void unknownAccountIs404() throws Exception {
        mvc.perform(get("/v1/accounts/{id}", "does-not-exist").with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }
}
