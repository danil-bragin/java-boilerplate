package com.acme.bank.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One-time benchmark fixture: opens a pool of source + destination accounts through the gateway
 * (funding the sources generously so transfers stay well under the antifraud 10,000 USD limit), and
 * can seed a target account's ledger to a requested DEPTH so the read-path simulation can measure how
 * the derived-balance SUM read cost grows with ledger size (the price of the no-materialization
 * choice). Built once at simulation start; the resulting ids are fed to the scenarios.
 *
 * <p>Talks to the gateway exactly as an external client (java.net.http + bearer + Idempotency-Key),
 * reusing the e2e API shapes: {@code POST /v1/accounts}, {@code POST /v1/transfers}.
 */
public final class Setup {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    // Each funded source starts with a large balance so thousands of small transfers never overdraw.
    private static final String SOURCE_FUNDING = "9000000.00";

    private final String gatewayUrl;
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private final List<String> sources = new ArrayList<>();
    private final List<String> destinations = new ArrayList<>();
    private String hotSource;
    private String readTarget;

    public Setup() {
        this.gatewayUrl = BenchEnv.gatewayUrl();
    }

    /** Pre-open {@code poolSize} source/destination pairs and fund every source. */
    public Setup openPool(String token, int poolSize) {
        for (int i = 0; i < poolSize; i++) {
            sources.add(openAccount(token, "Bench Source " + i, SOURCE_FUNDING));
            destinations.add(openAccount(token, "Bench Dest " + i, null));
        }
        return this;
    }

    /** Open ONE generously funded source shared by all virtual users (hot-account write mode). */
    public Setup openHotSource(String token) {
        hotSource = openAccount(token, "Bench Hot Source", SOURCE_FUNDING);
        return this;
    }

    /**
     * Open a destination and drive its ledger to {@code depth} entries by issuing {@code depth} tiny
     * transfers into it from a dedicated funded source. Each completed transfer adds a credit posting,
     * growing the derived-balance SUM the read path must aggregate. Returns the target id.
     */
    public Setup seedReadTarget(String token, int depth) {
        String funder = openAccount(token, "Bench Read Funder", SOURCE_FUNDING);
        readTarget = openAccount(token, "Bench Read Target", null);
        for (int i = 0; i < depth; i++) {
            postTransfer(token, funder, readTarget, "1.00");
            // Brief pacing every 100 to avoid overrunning the saga pipeline during seeding.
            if (i % 100 == 99) {
                sleep(50);
            }
        }
        return this;
    }

    public List<String> sources() {
        return List.copyOf(sources);
    }

    public List<String> destinations() {
        return List.copyOf(destinations);
    }

    public String hotSource() {
        return hotSource;
    }

    public String readTarget() {
        return readTarget;
    }

    /** Open an account; returns the accountId. */
    public String openAccount(String token, String ownerName, String initialDeposit) {
        String body = initialDeposit == null
                ? "{\"ownerName\":\"" + ownerName + "\",\"asset\":\"USD\"}"
                : "{\"ownerName\":\"" + ownerName + "\",\"asset\":\"USD\",\"initialDeposit\":{\"value\":\""
                        + initialDeposit + "\",\"asset\":\"USD\"}}";
        JsonNode node = post("/v1/accounts", body, token, 201);
        return node.get("accountId").asText();
    }

    /** Request a transfer; returns the transferId. */
    public String postTransfer(String token, String source, String destination, String amount) {
        String body = "{\"sourceAccountId\":\"" + source + "\",\"destinationAccountId\":\"" + destination
                + "\",\"amount\":{\"value\":\"" + amount + "\",\"asset\":\"USD\"}}";
        JsonNode node = post("/v1/transfers", body, token, 202);
        return node.get("transferId").asText();
    }

    private JsonNode post(String path, String body, String token, int expectedStatus) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(gatewayUrl + path))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            if (response.statusCode() != expectedStatus) {
                throw new IllegalStateException("Setup " + path + " expected " + expectedStatus + " but got "
                        + response.statusCode() + ": " + response.body());
            }
            return MAPPER.readTree(response.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Setup call to " + path + " failed: " + e.getMessage(), e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
