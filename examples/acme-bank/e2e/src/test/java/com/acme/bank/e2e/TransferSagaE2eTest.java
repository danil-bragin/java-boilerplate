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
 * Happy-path money-movement saga driven end-to-end through the gateway's public HTTP edge with a REAL
 * Keycloak token. Proves the whole BANK-0..9 system wires up: open two accounts, request a transfer,
 * and the saga (requested -> screened -> posting -> ledger-posted -> completed) settles to COMPLETED
 * with the money actually moved and the destination statement showing the credit.
 *
 * <p>ONE stack per class (shared across all assertions) to bound the heavy startup cost.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransferSagaE2eTest {

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
    void transferSagaCompletesAndMovesMoney() {
        // Open the source (1000.00 USD opening deposit) and an empty destination.
        String source = openAccount("Alice Source", "1000.00");
        String destination = openAccount("Bob Destination", null);

        // Request a 250.00 USD transfer through the gateway.
        String transferBody =
                """
                {"sourceAccountId":"%s","destinationAccountId":"%s","amount":{"value":"250.00","asset":"USD"}}"""
                        .formatted(source, destination);
        HttpJson.Response accepted = http.post(
                "/v1/transfers", transferBody, token, UUID.randomUUID().toString());
        assertThat(accepted.status()).isEqualTo(202);
        String transferId = accepted.body().get("transferId").asText();
        assertThat(transferId).isNotBlank();

        // The full saga must settle to COMPLETED in the gateway's projection.
        await().atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    HttpJson.Response view = http.get("/v1/transfers/" + transferId, token);
                    assertThat(view.status()).isEqualTo(200);
                    assertThat(view.body().get("status").asText()).isEqualTo("COMPLETED");
                });

        // Money actually moved: source 750.00, destination 250.00.
        assertThat(balance(source)).isEqualTo("750.00");
        assertThat(balance(destination)).isEqualTo("250.00");

        // The destination statement shows the +250.00 credit.
        HttpJson.Response statement = http.get("/v1/accounts/" + destination + "/statement", token);
        assertThat(statement.status()).isEqualTo(200);
        JsonNode lines = statement.body().get("lines");
        assertThat(lines).isNotNull();
        boolean hasCredit = false;
        for (JsonNode line : lines) {
            if ("250.00".equals(line.get("signedAmount").asText())) {
                hasCredit = true;
                break;
            }
        }
        assertThat(hasCredit)
                .as("destination statement should contain a +250.00 credit line")
                .isTrue();
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
