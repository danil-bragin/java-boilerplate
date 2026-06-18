package com.acme.bank.bench;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.asLongAs;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.pace;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Saga settle latency: {@code POST /v1/transfers} then POLL {@code GET /v1/transfers/{id}} until the
 * status is terminal (COMPLETED / FAILED), capturing the END-TO-END settle time (request → terminal)
 * as the {@code saga-settle} Gatling group's cumulative response time. This quantifies the
 * eventual-consistency latency of the multi-hop outbox-poll + Kafka path UNDER LOAD — distinct from
 * the POST's own (202) response time. Driven at a modest arrival rate ({@code BENCH_RATE}) so the saga
 * pipeline is exercised but not trivially overwhelmed; raise the rate across runs to find its knee.
 */
public class SagaSettleSimulation extends Simulation {

    private static final int MAX_POLLS = 60;

    private final TokenPool tokens = TokenPool.fetch(BenchEnv.tokenCount());
    private final Setup setup;

    private final HttpProtocolBuilder httpProtocol = http.baseUrl(BenchEnv.gatewayUrl())
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .shareConnections();

    public SagaSettleSimulation() {
        Setup s = new Setup();
        s.openPool(tokens.any(), BenchEnv.poolSize());
        this.setup = s;
    }

    private ScenarioBuilder scn() {
        List<String> sources = setup.sources();
        List<String> destinations = setup.destinations();
        return scenario("saga-settle")
                .group("saga-settle")
                .on(exec(session -> {
                            int n = ThreadLocalRandom.current().nextInt(sources.size());
                            String src = sources.get(n);
                            String dst = destinations.get((n + 1) % destinations.size());
                            String body = "{\"sourceAccountId\":\"" + src + "\",\"destinationAccountId\":\"" + dst
                                    + "\",\"amount\":{\"value\":\"1.00\",\"asset\":\"USD\"}}";
                            return session.set("body", body)
                                    .set("token", tokens.next())
                                    .set("idem", UUID.randomUUID().toString())
                                    .set("status", "PENDING")
                                    .set("polls", 0);
                        })
                        .exec(http("POST /v1/transfers")
                                .post("/v1/transfers")
                                .header("Authorization", session -> "Bearer " + session.getString("token"))
                                .header("Idempotency-Key", session -> session.getString("idem"))
                                .body(StringBody(session -> session.getString("body")))
                                .check(status().is(202))
                                .check(jsonPath("$.transferId").saveAs("transferId")))
                        .exec(asLongAs(session ->
                                        !isTerminal(session.getString("status")) && session.getInt("polls") < MAX_POLLS)
                                .on(pace(Duration.ofMillis(500))
                                        .exec(http("GET /v1/transfers/{id} (poll)")
                                                .get(session -> "/v1/transfers/" + session.getString("transferId"))
                                                .header(
                                                        "Authorization",
                                                        session -> "Bearer " + session.getString("token"))
                                                .check(status().is(200))
                                                .check(jsonPath("$.status").saveAs("status")))
                                        .exec(session -> session.set("polls", session.getInt("polls") + 1)))));
    }

    private static boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status);
    }

    {
        // Open arrival-rate model: a fixed POST-then-settle rate over the hold window.
        int rate = Math.max(1, BenchEnv.rate() / 5); // settle is multi-second; keep the arrival rate gentle
        setUp(scn().injectOpen(constantUsersPerSec(rate)
                        .during(Duration.ofSeconds(BenchEnv.rampSeconds() + BenchEnv.holdSeconds()))))
                .protocols(httpProtocol)
                .maxDuration(Duration.ofSeconds(BenchEnv.rampSeconds() + BenchEnv.holdSeconds() + 60));
    }
}
