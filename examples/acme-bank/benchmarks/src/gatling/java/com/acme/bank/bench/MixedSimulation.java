package com.acme.bank.bench;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.percent;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Realistic blended profile at a FIXED arrival rate ({@code BENCH_RATE} req/s): ~70% reads (derived
 * balance), ~25% cross-account transfers, ~5% account opens. Raise {@code BENCH_RATE} across runs to
 * find the blend's knee (where p99 or error% takes off). Uses an OPEN arrival-rate model so the
 * offered load is independent of how fast the system answers — the right way to find saturation.
 */
public class MixedSimulation extends Simulation {

    private final TokenPool tokens = TokenPool.fetch(BenchEnv.tokenCount());
    private final Setup setup;
    private final String readTarget;

    private final HttpProtocolBuilder httpProtocol = http.baseUrl(BenchEnv.gatewayUrl())
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .shareConnections();

    public MixedSimulation() {
        Setup s = new Setup();
        String token = tokens.any();
        s.openPool(token, BenchEnv.poolSize());
        // A modest pre-seeded read target so the read leg exercises a non-trivial derived balance.
        s.seedReadTarget(token, Math.min(BenchEnv.ledgerDepth(), 50));
        this.setup = s;
        this.readTarget = s.readTarget();
    }

    private ChainBuilder readChain() {
        return exec(http("MIX GET balance")
                .get("/v1/accounts/" + readTarget + "/balance")
                .header("Authorization", session -> "Bearer " + session.getString("token"))
                .check(status().is(200)));
    }

    private ChainBuilder transferChain() {
        List<String> sources = setup.sources();
        List<String> destinations = setup.destinations();
        return exec(session -> {
                    int n = ThreadLocalRandom.current().nextInt(sources.size());
                    String src = sources.get(n);
                    String dst = destinations.get((n + 1) % destinations.size());
                    String body = "{\"sourceAccountId\":\"" + src + "\",\"destinationAccountId\":\"" + dst
                            + "\",\"amount\":{\"value\":\"1.00\",\"asset\":\"USD\"}}";
                    return session.set("body", body)
                            .set("idem", UUID.randomUUID().toString());
                })
                .exec(http("MIX POST /v1/transfers")
                        .post("/v1/transfers")
                        .header("Authorization", session -> "Bearer " + session.getString("token"))
                        .header("Idempotency-Key", session -> session.getString("idem"))
                        .body(StringBody(session -> session.getString("body")))
                        .check(status().is(202)));
    }

    private ChainBuilder openChain() {
        return exec(session ->
                        session.set("idem", UUID.randomUUID().toString()).set("owner", "Mix Open " + UUID.randomUUID()))
                .exec(http("MIX POST /v1/accounts")
                        .post("/v1/accounts")
                        .header("Authorization", session -> "Bearer " + session.getString("token"))
                        .header("Idempotency-Key", session -> session.getString("idem"))
                        .body(StringBody(session -> "{\"ownerName\":\"" + session.getString("owner")
                                + "\",\"asset\":\"USD\",\"initialDeposit\":{\"value\":\"100.00\",\"asset\":\"USD\"}}"))
                        .check(status().is(201)));
    }

    private ScenarioBuilder scn() {
        return scenario("mixed-profile")
                .exec(session -> session.set("token", tokens.next()))
                .randomSwitch()
                .on(
                        percent(70).then(readChain()),
                        percent(25).then(transferChain()),
                        percent(5).then(openChain()));
    }

    {
        setUp(scn().injectOpen(constantUsersPerSec(BenchEnv.rate())
                        .during(Duration.ofSeconds(BenchEnv.rampSeconds() + BenchEnv.holdSeconds()))))
                .protocols(httpProtocol)
                .maxDuration(Duration.ofSeconds(BenchEnv.rampSeconds() + BenchEnv.holdSeconds() + 30));
    }
}
