package com.acme.bank.bench;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.UUID;

/**
 * Account-creation throughput: {@code POST /v1/accounts} with an opening deposit. The open path also
 * takes a write transaction plus an opening posting, so this measures the account-creation write
 * ceiling — a useful comparison point against the transfer write path (transfers additionally cross
 * the saga / Kafka boundary).
 */
public class OpenAccountSimulation extends Simulation {

    private final TokenPool tokens = TokenPool.fetch(BenchEnv.tokenCount());

    private final HttpProtocolBuilder httpProtocol = http.baseUrl(BenchEnv.gatewayUrl())
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .shareConnections();

    private ScenarioBuilder scn() {
        return scenario("open-account")
                .during(Duration.ofSeconds(BenchEnv.rampSeconds() + BenchEnv.holdSeconds()))
                .on(exec(session -> session.set("token", tokens.next())
                                .set("idem", UUID.randomUUID().toString())
                                .set("owner", "Bench Open " + UUID.randomUUID()))
                        .exec(http("POST /v1/accounts")
                                .post("/v1/accounts")
                                .header("Authorization", session -> "Bearer " + session.getString("token"))
                                .header("Idempotency-Key", session -> session.getString("idem"))
                                .body(
                                        StringBody(
                                                session -> "{\"ownerName\":\"" + session.getString("owner")
                                                        + "\",\"asset\":\"USD\",\"initialDeposit\":{\"value\":\"100.00\",\"asset\":\"USD\"}}"))
                                .check(status().is(201))));
    }

    public OpenAccountSimulation() {
        setUp(scn().injectClosed(
                                rampConcurrentUsers(1)
                                        .to(BenchEnv.users())
                                        .during(Duration.ofSeconds(BenchEnv.rampSeconds())),
                                constantConcurrentUsers(BenchEnv.users())
                                        .during(Duration.ofSeconds(BenchEnv.holdSeconds()))))
                .protocols(httpProtocol)
                .maxDuration(Duration.ofSeconds(BenchEnv.rampSeconds() + BenchEnv.holdSeconds() + 30));
    }
}
