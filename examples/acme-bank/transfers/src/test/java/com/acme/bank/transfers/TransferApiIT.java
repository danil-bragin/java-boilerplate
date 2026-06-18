package com.acme.bank.transfers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfiguration.class)
class TransferApiIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    private static final String VALID =
            "{\"sourceAccountId\":\"a\",\"destinationAccountId\":\"b\",\"amount\":\"100.00\",\"asset\":\"USD\"}";

    @Test
    void unauthenticatedIsRejected() throws Exception {
        mvc.perform(post("/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void initiatesTransfer() throws Exception {
        mvc.perform(post("/v1/transfers")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.transferId").exists())
                .andExpect(jsonPath("$.status").value("REQUESTED"));
    }

    @Test
    void rejectsInvalidBody() throws Exception {
        String bad = "{\"sourceAccountId\":\"a\",\"destinationAccountId\":\"b\",\"amount\":\"\",\"asset\":\"USD\"}";
        mvc.perform(post("/v1/transfers")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void idempotencyKeyReplaysAndPersistsOnce() throws Exception {
        long before = jdbc.queryForObject("SELECT count(*) FROM transfer", Long.class);

        String first = mvc.perform(post("/v1/transfers")
                        .with(jwt())
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String second = mvc.perform(post("/v1/transfers")
                        .with(jwt())
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode a = mapper.readTree(first);
        JsonNode b = mapper.readTree(second);
        assertThat(b.get("transferId").asText()).isEqualTo(a.get("transferId").asText()); // replayed

        long after = jdbc.queryForObject("SELECT count(*) FROM transfer", Long.class);
        assertThat(after).isEqualTo(before + 1); // persisted exactly once
    }

    @Test
    void getsStatus() throws Exception {
        String body = mvc.perform(post("/v1/transfers")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = mapper.readTree(body).get("transferId").asText();

        mvc.perform(get("/v1/transfers/{id}", id).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REQUESTED"));
    }
}
