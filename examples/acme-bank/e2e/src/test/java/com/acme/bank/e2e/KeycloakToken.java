package com.acme.bank.e2e;

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

/**
 * Fetches a REAL access token from the Keycloak container via the OAuth2 password (direct-access) grant
 * against the {@code bank} realm's public {@code bank-gateway} client. The whole auth path is exercised
 * for real — no mocked decoder. The token's {@code iss} is pinned by the compose Keycloak config to
 * {@code http://keycloak:8080/realms/bank} (the URL the services validate), regardless of the caller's
 * host, so a token fetched here from the host's mapped Keycloak port is accepted by the services.
 */
final class KeycloakToken {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CLIENT_ID = "bank-gateway";

    private KeycloakToken() {}

    /** Obtain an access token for {@code username}/{@code password} from the realm at {@code keycloakBaseUrl}. */
    static String fetch(String keycloakBaseUrl, String username, String password) {
        String base = keycloakBaseUrl.endsWith("/")
                ? keycloakBaseUrl.substring(0, keycloakBaseUrl.length() - 1)
                : keycloakBaseUrl;
        String tokenUrl = base + "/realms/bank/protocol/openid-connect/token";
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
