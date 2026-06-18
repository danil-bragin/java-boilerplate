package com.acme.bank.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Negative and edge e2e scenarios through the gateway, all sharing ONE stack (bounded startup cost):
 *
 * <ul>
 *   <li><b>rejected</b> — an amount above the antifraud {@code AMOUNT_LIMIT} (10000 USD) settles to
 *       FAILED and moves NO money.
 *   <li><b>idempotency</b> — the same {@code Idempotency-Key} + body yields the same transfer and
 *       debits the source exactly once (no double-spend).
 *   <li><b>auth-negative</b> — no bearer / a garbage bearer is 401 at the edge.
 * </ul>
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SagaScenariosE2eTest {

    private final StackContainers stack = new StackContainers();
    private HttpJson http;
    private String token;

    @BeforeAll
    void startStack() {
        stack.start();
        http = new HttpJson(stack.gatewayBaseUrl());
        token = KeycloakToken.fetch(stack.keycloakBaseUrl(), "alice", "alice");
    }

    @AfterAll
    void stopStack() {
        stack.stop();
    }

    @Test
    void transferAboveAntifraudLimitFailsAndMovesNoMoney() {
        String source = openAccount("Rich Source", "50000.00");
        String destination = openAccount("Empty Destination", null);

        // 25000.00 is above the antifraud AMOUNT_LIMIT (10000 USD) -> the saga must reject it.
        String body =
                """
                {"sourceAccountId":"%s","destinationAccountId":"%s","amount":{"value":"25000.00","asset":"USD"}}"""
                        .formatted(source, destination);
        HttpJson.Response accepted =
                http.post("/v1/transfers", body, token, UUID.randomUUID().toString());
        assertThat(accepted.status()).isEqualTo(202);
        String transferId = accepted.body().get("transferId").asText();

        await().atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    HttpJson.Response view = http.get("/v1/transfers/" + transferId, token);
                    assertThat(view.status()).isEqualTo(200);
                    assertThat(view.body().get("status").asText()).isEqualTo("FAILED");
                });

        // The failure reason is surfaced and NO money moved.
        HttpJson.Response view = http.get("/v1/transfers/" + transferId, token);
        JsonNode reason = view.body().get("failureReason");
        assertThat(reason).isNotNull();
        assertThat(reason.asText()).isNotBlank();
        assertThat(balance(source)).isEqualTo("50000.00");
        assertThat(balance(destination)).isEqualTo("0.00");
    }

    @Test
    void sameIdempotencyKeyYieldsOneTransferAndDebitsSourceOnce() {
        String source = openAccount("Idem Source", "1000.00");
        String destination = openAccount("Idem Destination", null);

        String key = UUID.randomUUID().toString();
        String body =
                """
                {"sourceAccountId":"%s","destinationAccountId":"%s","amount":{"value":"100.00","asset":"USD"}}"""
                        .formatted(source, destination);

        HttpJson.Response first = http.post("/v1/transfers", body, token, key);
        HttpJson.Response second = http.post("/v1/transfers", body, token, key);
        assertThat(first.status()).isEqualTo(202);
        assertThat(second.status()).isEqualTo(202);
        String firstId = first.body().get("transferId").asText();
        String secondId = second.body().get("transferId").asText();
        assertThat(secondId).as("idempotent replay returns the same transferId").isEqualTo(firstId);

        // Let the single transfer settle, then assert exactly one transfer exists for the source.
        await().atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    HttpJson.Response view = http.get("/v1/transfers/" + firstId, token);
                    assertThat(view.status()).isEqualTo(200);
                    assertThat(view.body().get("status").asText()).isEqualTo("COMPLETED");
                });

        HttpJson.Response list = http.get("/v1/transfers?accountId=" + source, token);
        assertThat(list.status()).isEqualTo(200);
        JsonNode content = list.body().get("content");
        assertThat(content).isNotNull();
        assertThat(content.size())
                .as("exactly one transfer for the source — no double-spend")
                .isEqualTo(1);

        // Source debited once (1000 - 100 = 900), not twice.
        assertThat(balance(source)).isEqualTo("900.00");
        assertThat(balance(destination)).isEqualTo("100.00");
    }

    @Test
    void unauthenticatedTransferIs401() {
        String body =
                """
                {"sourceAccountId":"a","destinationAccountId":"b","amount":{"value":"10.00","asset":"USD"}}""";

        // No bearer at all.
        HttpJson.Response noToken =
                http.post("/v1/transfers", body, null, UUID.randomUUID().toString());
        assertThat(noToken.status()).isEqualTo(401);

        // A malformed/garbage bearer.
        HttpJson.Response badToken = http.post(
                "/v1/transfers", body, "not-a-real-jwt", UUID.randomUUID().toString());
        assertThat(badToken.status()).isEqualTo(401);
    }

    private String openAccount(String ownerName, String initialDeposit) {
        String body = initialDeposit == null
                ? """
                {"ownerName":"%s","asset":"USD"}""".formatted(ownerName)
                : """
                {"ownerName":"%s","asset":"USD","initialDeposit":{"value":"%s","asset":"USD"}}"""
                        .formatted(ownerName, initialDeposit);
        HttpJson.Response response =
                http.post("/v1/accounts", body, token, UUID.randomUUID().toString());
        assertThat(response.status()).as("open account %s", ownerName).isEqualTo(201);
        return response.body().get("accountId").asText();
    }

    private String balance(String accountId) {
        HttpJson.Response response = http.get("/v1/accounts/" + accountId + "/balance", token);
        assertThat(response.status()).isEqualTo(200);
        return response.body().get("value").asText();
    }
}
