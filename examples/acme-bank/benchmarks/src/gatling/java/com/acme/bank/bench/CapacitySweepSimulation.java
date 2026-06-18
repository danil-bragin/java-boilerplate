package com.acme.bank.bench;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.percent;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.OpenInjectionStep;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * BANK-18 capacity sweep — the OPEN-model ramp-to-knee that finds the REAL maximum sustainable QPS of
 * the acme-bank stack, distinct from the BANK-13 closed-loop simulations.
 *
 * <p>The BANK-13/17 write number (~5–10 req/s) was NOT capacity — it was four stacked MEASUREMENT
 * ARTIFACTS: the gateway's per-caller rate limit (100 req/min/IP, single-IP load generator), the
 * circuit breaker shedding under co-located CPU starvation, resilience4j retry amplification, and a
 * closed-loop zero-think Gatling profile that collapsed past the breaker. This simulation removes the
 * first three via {@code compose.bench.yaml} env overrides (prod defaults unchanged) and the fourth by
 * using an arrival-rate ({@code rampUsersPerSec}) injection — offered load is independent of how fast
 * the system answers, the only correct way to find a saturation knee — and ramps each path to where
 * error% &gt; 1% or p99 knees.
 *
 * <p>Mode is chosen with {@code -DBENCH_CAPACITY_MODE=write|read|mixed} (default {@code write}):
 *
 * <ul>
 *   <li><b>write</b>: {@code POST /v1/transfers}, DISTINCT source accounts from a LARGE {@code
 *       BENCH_POOL_SIZE} pool (so the BANK-11 source lock never serializes the measurement — we want
 *       machine capacity across distinct accounts), unique idempotency keys. Records the max
 *       POST-ACCEPT QPS; the SUSTAINABLE saga rate (lag-bounded drain) is read separately from {@code
 *       rpk group describe}.
 *   <li><b>read</b>: {@code GET balance} (derived SUM) + {@code GET statement} against a pre-seeded
 *       target. Records the max read QPS.
 *   <li><b>mixed</b>: 70/25/5 read/write/open blend at the ramped arrival rate.
 * </ul>
 *
 * <p>Knob: {@code rampUsersPerSec(BENCH_RAMP_START).to(BENCH_PEAK_RPS).during(BENCH_RAMP_SECONDS)} then
 * a constant hold at {@code BENCH_PEAK_RPS} for {@code BENCH_HOLD_SECONDS}. Set {@code BENCH_PEAK_RPS}
 * to a rate comfortably above the expected knee and read the per-second RPS / p99 / error% off the
 * Gatling report; the knee is the highest rate where err &lt; 1% and p99 stays below target.
 */
public class CapacitySweepSimulation extends Simulation {

    private final TokenPool tokens = TokenPool.fetch(BenchEnv.tokenCount());
    private final Setup setup;
    private final String mode =
            BenchEnv.prop("BENCH_CAPACITY_MODE", "write").trim().toLowerCase();
    private final String readTarget;

    private final HttpProtocolBuilder httpProtocol = http.baseUrl(BenchEnv.gatewayUrl())
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .shareConnections();

    public CapacitySweepSimulation() {
        Setup s = new Setup();
        String token = tokens.any();
        ScenarioBuilder scn;
        switch (mode) {
            case "read" -> {
                s.seedReadTarget(token, BenchEnv.ledgerDepth());
                this.readTarget = s.readTarget();
                scn = readScenario();
            }
            case "mixed" -> {
                s.openPool(token, BenchEnv.poolSize());
                s.seedReadTarget(token, Math.min(BenchEnv.ledgerDepth(), 50));
                this.readTarget = s.readTarget();
                scn = mixedScenario();
            }
            default -> {
                // write — a LARGE distinct-source pool so the source lock isn't what's measured.
                s.openPool(token, BenchEnv.poolSize());
                this.readTarget = null;
                scn = writeScenario();
            }
        }
        this.setup = s;

        setUp(scn.injectOpen(ramp()).protocols(httpProtocol))
                .maxDuration(Duration.ofSeconds(BenchEnv.rampSeconds() + BenchEnv.holdSeconds() + 30));
    }

    /** ramp BENCH_RAMP_START → BENCH_PEAK_RPS over the ramp window, then hold at peak. */
    private OpenInjectionStep[] ramp() {
        return new OpenInjectionStep[] {
            rampUsersPerSec(BenchEnv.rampStartRps())
                    .to(BenchEnv.peakRps())
                    .during(Duration.ofSeconds(BenchEnv.rampSeconds())),
            io.gatling.javaapi.core.CoreDsl.constantUsersPerSec(BenchEnv.peakRps())
                    .during(Duration.ofSeconds(BenchEnv.holdSeconds()))
        };
    }

    private ScenarioBuilder writeScenario() {
        return scenario("capacity-write").exec(transferChain());
    }

    private ScenarioBuilder readScenario() {
        return scenario("capacity-read").exec(readChain());
    }

    private ScenarioBuilder mixedScenario() {
        return scenario("capacity-mixed")
                .randomSwitch()
                .on(
                        percent(70).then(readChain()),
                        percent(25).then(transferChain()),
                        percent(5).then(openChain()));
    }

    private ChainBuilder transferChain() {
        return exec(session -> {
                    List<String> sources = setup.sources();
                    List<String> destinations = setup.destinations();
                    int n = ThreadLocalRandom.current().nextInt(sources.size());
                    String src = sources.get(n);
                    String dst = destinations.get((n + 1) % destinations.size());
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
                        .check(status().is(202)));
    }

    private ChainBuilder readChain() {
        return exec(session -> session.set("token", tokens.next()))
                .exec(http("GET balance (derived SUM)")
                        .get("/v1/accounts/" + readTarget + "/balance")
                        .header("Authorization", session -> "Bearer " + session.getString("token"))
                        .check(status().is(200)))
                .exec(http("GET statement")
                        .get("/v1/accounts/" + readTarget + "/statement?page=0&size=20")
                        .header("Authorization", session -> "Bearer " + session.getString("token"))
                        .check(status().is(200)));
    }

    private ChainBuilder openChain() {
        return exec(session -> session.set("token", tokens.next())
                        .set("idem", UUID.randomUUID().toString())
                        .set("owner", "Cap Open " + UUID.randomUUID()))
                .exec(http("POST /v1/accounts")
                        .post("/v1/accounts")
                        .header("Authorization", session -> "Bearer " + session.getString("token"))
                        .header("Idempotency-Key", session -> session.getString("idem"))
                        .body(StringBody(session -> "{\"ownerName\":\"" + session.getString("owner")
                                + "\",\"asset\":\"USD\",\"initialDeposit\":{\"value\":\"100.00\",\"asset\":\"USD\"}}"))
                        .check(status().is(201)));
    }
}
