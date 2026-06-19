package com.acme.bank.bench;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers;
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
 * Write-path load: {@code POST /v1/transfers} with a bearer + a UNIQUE {@code Idempotency-Key} per
 * request, a small antifraud-passing amount (well under the 10,000 USD limit). This simulation exposes
 * the BANK-11 pessimistic source-account lock by running in one of two modes (set {@code
 * -DBENCH_SOURCE_MODE}):
 *
 * <ul>
 *   <li><b>cross</b> (default): every request uses a DISTINCT source account drawn from the pre-opened
 *       pool, so postings DON'T contend on the source lock → measures the system's PARALLEL write
 *       ceiling (DB / Kafka / CPU bound).
 *   <li><b>hot</b>: ALL requests hit ONE generously-funded source account, so every posting serializes
 *       on the pessimistic source lock → measures the PER-ACCOUNT write ceiling (the lock is the
 *       bottleneck by design).
 * </ul>
 *
 * Compare the two runs' RPS at the same injected load: the ratio is the per-account serialization cost.
 */
public class TransferWriteSimulation extends Simulation {

    private final TokenPool tokens = TokenPool.fetch(BenchEnv.tokenCount());
    private final Setup setup;
    private final boolean hot = BenchEnv.hotSource();

    private final HttpProtocolBuilder httpProtocol = http.baseUrl(BenchEnv.gatewayUrl())
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .shareConnections();

    public TransferWriteSimulation() {
        Setup s = new Setup();
        String token = tokens.any();
        if (hot) {
            s.openHotSource(token).openPool(token, BenchEnv.poolSize());
        } else {
            s.openPool(token, BenchEnv.poolSize());
        }
        this.setup = s;

        ScenarioBuilder scn = scenario("transfer-write-" + BenchEnv.sourceMode());
        if (BenchEnv.arrivalRate() > 0) {
            // OPEN model: one request per arrival at a fixed offered rate — the controlled way to find
            // the saturation knee (a closed model with zero think-time collapses past the breaker).
            setUp(scn.exec(oneTransfer())
                            .injectOpen(constantUsersPerSec(BenchEnv.arrivalRate())
                                    .during(Duration.ofSeconds(BenchEnv.rampSeconds() + BenchEnv.holdSeconds()))))
                    .protocols(httpProtocol)
                    .maxDuration(Duration.ofSeconds(BenchEnv.rampSeconds() + BenchEnv.holdSeconds() + 30));
        } else {
            // Closed model: a fixed concurrency loops requests for a sustained steady-state hold.
            setUp(scn.during(Duration.ofSeconds(BenchEnv.rampSeconds() + BenchEnv.holdSeconds()))
                            .on(oneTransfer())
                            .injectClosed(
                                    rampConcurrentUsers(1)
                                            .to(BenchEnv.users())
                                            .during(Duration.ofSeconds(BenchEnv.rampSeconds())),
                                    constantConcurrentUsers(BenchEnv.users())
                                            .during(Duration.ofSeconds(BenchEnv.holdSeconds()))))
                    .protocols(httpProtocol)
                    .maxDuration(Duration.ofSeconds(BenchEnv.rampSeconds() + BenchEnv.holdSeconds() + 30));
        }
    }

    /** One transfer POST, choosing the source per the cross/hot mode. */
    private ChainBuilder oneTransfer() {
        List<String> sources = setup.sources();
        List<String> destinations = setup.destinations();
        String hotSource = setup.hotSource();
        return exec(session -> {
                    int n = ThreadLocalRandom.current().nextInt(Math.max(1, destinations.size()));
                    String src = hot ? hotSource : sources.get(n % sources.size());
                    String dst = destinations.get(n % destinations.size());
                    // Cross-mode must use distinct src/dst; if they collide, shift the dest.
                    if (src.equals(dst)) {
                        dst = destinations.get((n + 1) % destinations.size());
                    }
                    String body = "{\"sourceAccountId\":\"" + src + "\",\"destinationAccountId\":\"" + dst
                            + "\",\"amount\":{\"value\":\"" + BenchEnv.transferAmount() + "\",\"asset\":\"USD\"}}";
                    return session.set("body", body)
                            .set("token", tokens.next())
                            .set("idem", UUID.randomUUID().toString());
                })
                .exec(http("POST /v1/transfers")
                        .post("/v1/transfers")
                        .header("Authorization", session -> "Bearer " + session.getString("token"))
                        .header("Idempotency-Key", session -> session.getString("idem"))
                        .body(StringBody(session -> session.getString("body")))
                        .check(status().is(202)));
    }
}
