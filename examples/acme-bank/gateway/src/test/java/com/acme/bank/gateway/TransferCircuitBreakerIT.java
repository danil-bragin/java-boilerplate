package com.acme.bank.gateway;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.test.PostgresTestcontainersConfiguration;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Drives the REAL {@link com.acme.bank.gateway.client.RestTransfersClient} (resilience4j proxy
 * intact) against an in-process HTTP server that we flip between failing (500) and healthy (200).
 *
 * <p>Proves the benchmark's "fallback unreachable → 500, never recovers" claim is wrong:
 *
 * <ol>
 *   <li>sustained 5xx OPENS the breaker, and a call against the open breaker
 *       ({@code CallNotPermittedException}) fires the (private) fallback → {@code 503} problem+json,
 *       NOT 500;
 *   <li>once the downstream heals the breaker HALF-OPENS and RECOVERS — a later call returns 202.
 * </ol>
 *
 * Unlike {@code AccountProxyIT}/{@code TransferControllerDownstream4xxIT}, this does NOT stub the
 * {@code *RestClient} bean (which would bypass resilience4j); it stubs the HTTP transport so the
 * real annotations run.
 */
@SpringBootTest(
        properties = {
            "spring.kafka.listener.auto-startup=false",
            // Small window + short open-state so the breaker opens and recovers within the test.
            "resilience4j.circuitbreaker.instances.transfers.sliding-window-type=COUNT_BASED",
            "resilience4j.circuitbreaker.instances.transfers.sliding-window-size=4",
            "resilience4j.circuitbreaker.instances.transfers.minimum-number-of-calls=4",
            "resilience4j.circuitbreaker.instances.transfers.failure-rate-threshold=50",
            "resilience4j.circuitbreaker.instances.transfers.wait-duration-in-open-state=1s",
            "resilience4j.circuitbreaker.instances.transfers.permitted-number-of-calls-in-half-open-state=2",
            // Don't let retry mask how many "logical" calls reach the breaker; 1 attempt = no retry.
            "resilience4j.retry.instances.transfers.max-attempts=1"
        })
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfiguration.class)
class TransferCircuitBreakerIT {

    private static HttpServer server;
    private static final AtomicBoolean HEALTHY = new AtomicBoolean(false);

    @BeforeAll
    static void startDownstream() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/transfers", exchange -> {
            byte[] body;
            int code;
            if (HEALTHY.get()) {
                body = "{\"transferId\":\"t-ok-1\"}".getBytes(StandardCharsets.UTF_8);
                code = 200;
            } else {
                body = "{\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
                code = 500;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    @AfterAll
    static void stopDownstream() {
        if (server != null) {
            server.stop(0);
        }
    }

    @DynamicPropertySource
    static void downstreamUrl(DynamicPropertyRegistry registry) {
        registry.add(
                "gateway.transfers.base-url",
                () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;

    private static final String VALID =
            "{\"sourceAccountId\":\"a\",\"destinationAccountId\":\"b\",\"amount\":{\"value\":\"100.00\",\"asset\":\"USD\"}}";

    @BeforeEach
    void resetBreaker() {
        HEALTHY.set(false);
        circuitBreakerRegistry.circuitBreaker("transfers").reset();
    }

    private void submitExpectingServiceUnavailableOr503() throws Exception {
        // Each submit either hits the downstream 500 (and the breaker counts it) or, once the breaker
        // is open, fails fast with CallNotPermittedException — both surface as 503 via the fallback.
        mvc.perform(post("/v1/transfers")
                        .with(jwt())
                        .header("Idempotency-Key", "idem-cb-" + System.nanoTime())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void sustainedDownstream5xxOpensBreakerAndReturns503NotRecoversTo202() throws Exception {
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("transfers");

        // (a) Drive enough failing calls to fill the window and open the breaker. Every response is a
        // 503 (never a 500): 5xx → fallback, then CallNotPermittedException → same fallback.
        for (int i = 0; i < 6; i++) {
            submitExpectingServiceUnavailableOr503();
        }
        Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> breaker.getState() == CircuitBreaker.State.OPEN);

        // The fallback fired on the OPEN circuit: a 503 problem+json, not a 500.
        mvc.perform(post("/v1/transfers")
                        .with(jwt())
                        .header("Idempotency-Key", "idem-cb-open")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value("TRANSFERS_UNAVAILABLE"));

        // (b) Heal the downstream; after wait-duration-in-open-state the breaker half-opens and the
        // probe call succeeds → recovers to CLOSED and the gateway returns 202.
        HEALTHY.set(true);
        Awaitility.await()
                .atMost(Duration.ofSeconds(8))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> mvc.perform(post("/v1/transfers")
                                .with(jwt())
                                .header("Idempotency-Key", "idem-cb-recover-" + System.nanoTime())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.transferId").value("t-ok-1")));
    }
}
