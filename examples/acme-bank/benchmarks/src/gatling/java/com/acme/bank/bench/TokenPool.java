package com.acme.bank.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pre-fetches a batch of REAL Keycloak access tokens (OAuth2 password / direct-access grant against
 * the {@code bank} realm's public {@code bank-gateway} client, user {@code alice}/{@code alice}) ONCE
 * at simulation setup and round-robins them across virtual users. This keeps the Keycloak token
 * endpoint OUT of the hot path so the measured bottleneck is the bank, not auth. The token's {@code
 * iss} is pinned by the compose Keycloak config to {@code http://keycloak:8080/realms/bank}, so a
 * token fetched here from the host's mapped port (8082) is accepted by the in-network services.
 *
 * <p>Mirrors the e2e {@code KeycloakToken} helper but builds a reusable pool.
 */
public final class TokenPool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CLIENT_ID = "bank-gateway";

    private final List<String> tokens;
    private final AtomicInteger cursor = new AtomicInteger();

    private TokenPool(List<String> tokens) {
        this.tokens = List.copyOf(tokens);
    }

    /** Fetch {@code count} tokens for {@code alice} from the configured Keycloak base URL. */
    public static TokenPool fetch(int count) {
        String base = BenchEnv.keycloakUrl();
        List<String> tokens = new ArrayList<>(count);
        for (int i = 0; i < Math.max(1, count); i++) {
            tokens.add(fetchOne(base, "alice", "alice"));
        }
        return new TokenPool(tokens);
    }

    /** The next token in round-robin order — thread-safe across Gatling's virtual users. */
    public String next() {
        int idx = Math.floorMod(cursor.getAndIncrement(), tokens.size());
        return tokens.get(idx);
    }

    /** Any single token (for setup-time calls that do not need rotation). */
    public String any() {
        return tokens.get(0);
    }

    private static String fetchOne(String keycloakBaseUrl, String username, String password) {
        String tokenUrl = keycloakBaseUrl + "/realms/bank/protocol/openid-connect/token";
        String form = "grant_type=password"
                + "&client_id=" + enc(CLIENT_ID)
                + "&username=" + enc(username)
                + "&password=" + enc(password);
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(BodyPublishers.ofString(form))
                .build();
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()) {
            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "Keycloak token request failed (" + response.statusCode() + "): " + response.body());
            }
            JsonNode node = MAPPER.readTree(response.body());
            JsonNode token = node.get("access_token");
            if (token == null || token.asText().isBlank()) {
                throw new IllegalStateException("No access_token in Keycloak response: " + response.body());
            }
            return token.asText();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Keycloak token fetch failed: " + e.getMessage(), e);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
