package com.acme.bank.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;

/**
 * Tiny java.net.http client for driving the gateway over HTTP in the e2e tests: GET/POST JSON with an
 * optional bearer token and {@code Idempotency-Key}. Bodies are parsed into a Jackson {@link JsonNode}.
 * Deliberately dependency-light — the e2e talks to the system exactly as an external client would.
 */
final class HttpJson {

    /** An HTTP outcome: the status code and the (possibly empty) parsed JSON body. */
    record Response(int status, JsonNode body) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String baseUrl;

    HttpJson(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    Response get(String path, String bearer) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().header("Accept", "application/json");
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return send(builder.build());
    }

    Response post(String path, String json, String bearer, String idempotencyKey) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .POST(BodyPublishers.ofString(json))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return send(builder.build());
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            String body = response.body();
            JsonNode node = (body == null || body.isBlank()) ? MAPPER.nullNode() : MAPPER.readTree(body);
            return new Response(response.statusCode(), node);
        } catch (Exception e) {
            throw new RuntimeException("HTTP call to " + request.uri() + " failed: " + e.getMessage(), e);
        }
    }
}
