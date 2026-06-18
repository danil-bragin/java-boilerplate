package com.acme.bank.bench;

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

/**
 * Read-path load against a target account whose ledger was pre-seeded to {@code BENCH_LEDGER_DEPTH}
 * entries (10 / 1k / 10k). It hammers:
 *
 * <ul>
 *   <li>{@code GET /v1/accounts/{id}/balance} — the DERIVED balance: a SUM over the ledger with NO
 *       materialized snapshot, so its cost grows with ledger depth (this simulation quantifies that
 *       growth — the price of the no-materialization choice).
 *   <li>{@code GET /v1/accounts/{id}/statement?page=&size=} — a paged ledger read.
 *   <li>{@code GET /v1/transfers/{id}} — the gateway projection read (a cheap point lookup, the
 *       contrast against the derived-balance aggregate).
 * </ul>
 *
 * Run once per depth and compare balance/statement latency to expose how read cost scales with size.
 */
public class ReadPathSimulation extends Simulation {

    private final TokenPool tokens = TokenPool.fetch(BenchEnv.tokenCount());
    private final Setup setup;
    private final String readTarget;
    private final String sampleTransferId;

    private final HttpProtocolBuilder httpProtocol = http.baseUrl(BenchEnv.gatewayUrl())
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .shareConnections();

    public ReadPathSimulation() {
        Setup s = new Setup();
        String token = tokens.any();
        // Seed the target to the requested ledger depth, then capture one transfer id for the
        // projection-read leg (a fresh small transfer into the seeded target).
        s.seedReadTarget(token, BenchEnv.ledgerDepth());
        this.setup = s;
        this.readTarget = s.readTarget();
        String funder = s.openAccount(token, "Bench Read Tx Source", "1000.00");
        this.sampleTransferId = s.postTransfer(token, funder, readTarget, "1.00");
    }

    private ScenarioBuilder scn() {
        return scenario("read-path-depth-" + BenchEnv.ledgerDepth())
                .during(Duration.ofSeconds(BenchEnv.rampSeconds() + BenchEnv.holdSeconds()))
                .on(exec(session -> session.set("token", tokens.next()))
                        .exec(http("GET balance (derived SUM, depth=" + BenchEnv.ledgerDepth() + ")")
                                .get("/v1/accounts/" + readTarget + "/balance")
                                .header("Authorization", session -> "Bearer " + session.getString("token"))
                                .check(status().is(200)))
                        .exec(http("GET statement (depth=" + BenchEnv.ledgerDepth() + ")")
                                .get("/v1/accounts/" + readTarget + "/statement?page=0&size=20")
                                .header("Authorization", session -> "Bearer " + session.getString("token"))
                                .check(status().is(200)))
                        .exec(http("GET transfer projection")
                                .get("/v1/transfers/" + sampleTransferId)
                                .header("Authorization", session -> "Bearer " + session.getString("token"))
                                .check(status().is(200))));
    }

    {
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
