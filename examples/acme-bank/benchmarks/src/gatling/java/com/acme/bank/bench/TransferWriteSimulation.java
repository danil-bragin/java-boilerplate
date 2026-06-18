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
    }

    private ScenarioBuilder scn() {
        List<String> sources = setup.sources();
        List<String> destinations = setup.destinations();
        String hotSource = setup.hotSource();
        // Closed model: each virtual user loops requests for the whole run so a fixed concurrency
        // produces a sustained steady-state hold (the request rate is whatever the system can absorb).
        return scenario("transfer-write-" + BenchEnv.sourceMode())
                .during(java.time.Duration.ofSeconds(BenchEnv.rampSeconds() + BenchEnv.holdSeconds()))
                .on(exec(session -> {
                            int n = ThreadLocalRandom.current().nextInt(Math.max(1, destinations.size()));
                            String src = hot ? hotSource : sources.get(n % sources.size());
                            String dst = destinations.get(n % destinations.size());
                            // Cross-mode must use distinct src/dst; if they collide, shift the dest.
                            if (src.equals(dst)) {
                                dst = destinations.get((n + 1) % destinations.size());
                            }
                            String body = "{\"sourceAccountId\":\"" + src + "\",\"destinationAccountId\":\"" + dst
                                    + "\",\"amount\":{\"value\":\"1.00\",\"asset\":\"USD\"}}";
                            return session.set("body", body)
                                    .set("token", tokens.next())
                                    .set("idem", UUID.randomUUID().toString());
                        })
                        .exec(http("POST /v1/transfers")
                                .post("/v1/transfers")
                                .header("Authorization", session -> "Bearer " + session.getString("token"))
                                .header("Idempotency-Key", session -> session.getString("idem"))
                                .body(StringBody(session -> session.getString("body")))
                                .check(status().is(202))));
    }

    {
        // Ramp concurrency to BENCH_USERS over the ramp, then hold it for the steady-state window.
        setUp(scn().injectClosed(
                                rampConcurrentUsers(1)
                                        .to(BenchEnv.users())
                                        .during(java.time.Duration.ofSeconds(BenchEnv.rampSeconds())),
                                constantConcurrentUsers(BenchEnv.users())
                                        .during(java.time.Duration.ofSeconds(BenchEnv.holdSeconds()))))
                .protocols(httpProtocol)
                .maxDuration(java.time.Duration.ofSeconds(BenchEnv.rampSeconds() + BenchEnv.holdSeconds() + 30));
    }
}
