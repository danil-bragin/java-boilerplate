package com.acme.bank.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.test.PostgresTestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Contract of the synchronous money-posting endpoint {@code POST /internal/postings} used by the
 * transfers fast-path. It routes to the SAME {@link com.acme.bank.accounts.application.PostTransferHandler}
 * as the async saga (lock + Σ=0 + posting-PK anchor), so it inherits idempotency and overdraft safety.
 * No bearer required — {@code /internal/**} is network-segmented.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.listener.auto-startup=false")
@Import(PostgresTestcontainersConfiguration.class)
class InternalPostingPostIT {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    private void openAccount(String id) {
        jdbc.update("INSERT INTO account(id, iban, status, asset) VALUES (?, ?, 'OPEN', 'USD')", id, "IBAN-" + id);
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

    private ResponseEntity<String> post(String transferId, String source, String dest, String amount) {
        String body = "{\"transferId\":\"" + transferId + "\",\"sourceAccountId\":\"" + source
                + "\",\"destinationAccountId\":\"" + dest + "\",\"amount\":{\"value\":\"" + amount
                + "\",\"asset\":\"USD\"}}";
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(
                "/internal/postings", new org.springframework.http.HttpEntity<>(body, headers), String.class);
    }

    @Test
    void postsFundedTransferAndDerivesBalances() throws Exception {
        openAccount("src1");
        openAccount("dst1");
        seedBalance("src1", "500.00");

        ResponseEntity<String> resp = post("p-1", "src1", "dst1", "120.00");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = mapper.readTree(resp.getBody());
        assertThat(json.get("transferId").asText()).isEqualTo("p-1");
        assertThat(json.get("status").asText()).isEqualTo("POSTED");

        java.math.BigDecimal sum = jdbc.queryForObject(
                "SELECT sum(amount) FROM ledger_entry WHERE transfer_id = 'p-1'", java.math.BigDecimal.class);
        assertThat(sum).isEqualByComparingTo("0");
        Long entries = jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE transfer_id = 'p-1'", Long.class);
        assertThat(entries).isEqualTo(2L);
    }

    @Test
    void rejectsInsufficientFundsWithoutMovingMoney() throws Exception {
        openAccount("poor1");
        openAccount("rich1");
        seedBalance("poor1", "10.00");

        ResponseEntity<String> resp = post("p-2", "poor1", "rich1", "100.00");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = mapper.readTree(resp.getBody());
        assertThat(json.get("status").asText()).isEqualTo("REJECTED");
        assertThat(json.get("reason").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        Long entries = jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE transfer_id = 'p-2'", Long.class);
        assertThat(entries).isEqualTo(0L);
    }

    @Test
    void repeatPostSameTransferIdIsIdempotent() throws Exception {
        openAccount("src3");
        openAccount("dst3");
        seedBalance("src3", "500.00");

        ResponseEntity<String> first = post("p-3", "src3", "dst3", "50.00");
        ResponseEntity<String> second = post("p-3", "src3", "dst3", "50.00");

        assertThat(mapper.readTree(first.getBody()).get("status").asText()).isEqualTo("POSTED");
        assertThat(mapper.readTree(second.getBody()).get("status").asText()).isEqualTo("POSTED");

        Long postings = jdbc.queryForObject("SELECT count(*) FROM posting WHERE transfer_id = 'p-3'", Long.class);
        assertThat(postings).isEqualTo(1L);
        Long entries = jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE transfer_id = 'p-3'", Long.class);
        assertThat(entries).isEqualTo(2L);
    }
}
