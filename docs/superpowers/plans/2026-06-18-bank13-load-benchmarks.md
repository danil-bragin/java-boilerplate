# BANK-13: load benchmarks — bottleneck discovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** Build a runnable load-test harness against the locally-deployed acme-bank stack (compose, all services + Keycloak + observability), measure throughput / latency percentiles / error rate per path, ramp to saturation to find the maximum sustainable traffic, identify the limiting resource for each path, and produce a benchmark report with measurements + bottleneck analysis + recommendations.

**Architecture:** A new `examples/acme-bank/benchmarks` module using **Gatling** (idiomatic JVM load tool, code-as-scenarios, percentile reports). It drives the gateway over HTTP with real Keycloak tokens. Scenarios are designed to EXPOSE the architecture's known pressure points: the BANK-11 source-account pessimistic lock (hot-account vs cross-account write throughput), the derived-balance SUM read cost (no materialization — read cost vs ledger depth), saga settle latency (eventual completion), DB pool + single-Postgres contention, and outbox/Kafka relay throughput. Benchmarks are `gatling`-task-only and excluded from `gradle build`.

**Tech Stack:** Gatling (`io.gatling.gradle` plugin), the BANK-9 compose stack, Keycloak password-grant token pool, Prometheus/Grafana (otel-lgtm) for resource metrics, java.net.http for setup.

> New requirement: local-deployment load benchmarks + bottleneck discovery. Builds on BANK-0..12 (the deployable stack + saga).
> **Environment (every task):** work from `/Users/npden4ik/Projects/java-boilerplate`, on `master`; system `gradle` (8.14) NEVER `./gradlew`; JDK 21; Docker up; all images cached (postgres/redpanda/redis/keycloak/otel-lgtm + the 5 service images build from BANK-9 Dockerfiles). A live load run needs the full stack up (5 JVM containers + infra + the Gatling JVM) — resource-heavy on a dev host; if the host can't sustain the target load, REDUCE the load and record what was achieved + the limiting host resource. `gradle <module>:spotlessApply` before commits.
> **Framing (state this in the report):** everything is co-located on one dev host, so absolute RPS is NOT production capacity. The deliverable is (a) a reusable harness and (b) RELATIVE bottleneck identification + scaling behavior (which path saturates first, hot vs cross-account, read-vs-write, where latency concentrates), which transfer to real deployments.

---

## Task 1: benchmarks module + Gatling scaffold + token/setup helpers

**Files:** `settings.gradle.kts` (include), `examples/acme-bank/benchmarks/build.gradle.kts`, `benchmarks/src/gatling/java/com/acme/bank/bench/BenchEnv.java`, `.../TokenPool.java`, `.../Setup.java`, `gradle/libs.versions.toml` (gatling plugin/version).

- [ ] **Step 1:** `settings.gradle.kts`: `include(":examples:acme-bank:benchmarks")`. Catalog: add the Gatling gradle plugin (`io.gatling.gradle` ~ 3.13.x matching a Gatling 3.13 line compatible with JDK 21).
- [ ] **Step 2:** `benchmarks/build.gradle.kts` — apply java + `id("io.gatling.gradle")`; the Gatling plugin adds the `src/gatling/java` source set + the `gatlingRun` task. Deps: gatling provided by the plugin; add jackson + a small http client for setup. NOT wired into `build`/`check` (the `gatlingRun` task is on-demand only). Configure Gatling to read the target base URL + token endpoint from system properties / env (`BENCH_GATEWAY_URL`, `BENCH_KEYCLOAK_URL`) with localhost defaults (8080 / 8082).
- [ ] **Step 3:** `BenchEnv` — resolves gateway/keycloak URLs + load params (`users`, `rampSeconds`, `holdSeconds`, `ledgerDepth`) from system properties with defaults. `TokenPool` — fetches a batch of Keycloak access tokens (password grant, `alice`) once and round-robins them (avoid the token endpoint being the bottleneck). `Setup` — opens a pool of source+destination accounts (via the gateway) and funds the sources, returning their ids for the simulations to feed.
- [ ] **Step 4:** `gradle :examples:acme-bank:benchmarks:compileGatling` (or `compileGatlingJava`) → compiles. Do NOT run load yet.
- [ ] **Step 5: commit**
```bash
gradle :examples:acme-bank:benchmarks:spotlessApply 2>/dev/null || true
git add settings.gradle.kts gradle/libs.versions.toml examples/acme-bank/benchmarks
git commit -m "bench: scaffold Gatling load-test module (token pool + account setup, on-demand only)"
```

---

## Task 2: write-path simulations — expose the source-lock ceiling

**Files:** `benchmarks/src/gatling/java/com/acme/bank/bench/TransferWriteSimulation.java` (+ a hot-source variant), `OpenAccountSimulation.java`.

- [ ] **Step 1:** `TransferWriteSimulation` — `POST /v1/transfers` with a bearer + a unique `Idempotency-Key` per request, amount well under the funded balance, antifraud-passing (< 10000). Two scenarios in the simulation (or two simulations, parameterized by `BENCH_SOURCE_MODE=cross|hot`):
  - **cross-account**: each virtual user uses a DISTINCT source account (from the `Setup` pool) → postings DON'T contend on the BANK-11 source lock → measures the system's parallel write ceiling (DB/Kafka/CPU bound).
  - **hot-account**: ALL virtual users hit ONE source account → every posting serializes on the pessimistic source lock → measures the per-account write ceiling (the lock is the bottleneck by design). Fund the hot source generously.
  - Both: inject a ramp (`rampUsers(BENCH_USERS) during rampSeconds`) then hold, capture p50/p95/p99 + RPS + error%.
- [ ] **Step 2:** `OpenAccountSimulation` — `POST /v1/accounts` ramp (the open path also takes a write tx + an opening posting). Measures account-creation throughput.
- [ ] **Step 3:** Compile. (Live runs happen in Task 4.)
- [ ] **Step 4: commit**
```bash
git add examples/acme-bank/benchmarks
git commit -m "bench: write-path simulations (cross-account vs hot-account transfer, open-account) to expose the source-lock ceiling"
```

---

## Task 3: read-path + saga-settle + mixed simulations

**Files:** `ReadPathSimulation.java`, `SagaSettleSimulation.java`, `MixedSimulation.java`.

- [ ] **Step 1:** `ReadPathSimulation` — `GET /v1/transfers/{id}` (gateway projection read) and `GET /v1/accounts/{id}/balance` + `GET .../statement` (derived-balance SUM — no materialization). Parameterize by `BENCH_LEDGER_DEPTH`: pre-seed the target accounts with N prior entries (10 / 1k / 10k) so the report can show how the derived-balance read latency grows with ledger size (the cost of the no-materialization choice). Ramp reads to saturation.
- [ ] **Step 2:** `SagaSettleSimulation` — `POST /v1/transfers` then POLL `GET /v1/transfers/{id}` until COMPLETED, recording the **end-to-end settle time** (request→terminal) as a Gatling custom timing / response-time distribution. This quantifies eventual-consistency latency (the multi-hop Kafka + outbox-poll path) under load — distinct from the POST response time.
- [ ] **Step 3:** `MixedSimulation` — a realistic blend (e.g. 70% reads, 25% transfers cross-account, 5% opens) at a fixed arrival rate (`constantUsersPerSec`), increasing the rate across runs to find the blend's knee.
- [ ] **Step 4:** Compile.
- [ ] **Step 5: commit**
```bash
git add examples/acme-bank/benchmarks
git commit -m "bench: read-path (derived-balance vs ledger depth), saga-settle latency, and mixed-profile simulations"
```

---

## Task 4: run the benchmarks against the live stack + capture results

**Files:** `examples/acme-bank/benchmarks/run-benchmarks.sh` (orchestration), raw Gatling reports under `benchmarks/results/` (gitignore the bulky HTML; keep the summary stats).

- [ ] **Step 1:** `run-benchmarks.sh` — bring the stack up (`gradle bankJars` → `docker compose -f examples/acme-bank/compose.bank.yaml up -d --build --wait`), wait for the gateway readiness, then run each simulation via `gradle :examples:acme-bank:benchmarks:gatlingRun -Dgatling.simulationClass=... -DBENCH_USERS=... ...`, then `docker compose ... down -v`. Parameterize load levels.
- [ ] **Step 2: RUN IT.** Start MODEST (e.g. 20–50 concurrent, 30s ramp + 60s hold) to validate the harness end-to-end, then increase until a path saturates (error% climbs / p99 knees / RPS plateaus). For each scenario record: max sustainable RPS (before error% > ~1% or p99 blows up), p50/p95/p99, error%. While a run holds steady-state, capture resource signals from the otel-lgtm/Prometheus (or `docker stats`): per-service CPU%, HikariCP active/pending connections (actuator `/actuator/metrics/hikaricp.connections.*` or Prometheus), Kafka consumer lag (redpanda), and GC/heap. **If the host can't drive high load**, reduce and record the achieved numbers + which host resource capped it (CPU saturation of co-located containers is the likely limiter — note it).
- [ ] **Step 3:** Save the per-scenario summary numbers (RPS, percentiles, error%, limiting resource) into the report (Task 5). Add `benchmarks/results/*.html` and Gatling's `simulation.log` to `.gitignore`; keep only the distilled `BENCHMARKS.md` numbers.
- [ ] **Step 4: commit**
```bash
git add examples/acme-bank/benchmarks/run-benchmarks.sh .gitignore
git commit -m "bench: orchestration script + run the load suite against the live compose stack"
```

---

## Task 5: BENCHMARKS.md report + ADR

**Files:** `examples/acme-bank/BENCHMARKS.md`, `docs/decisions/0024-performance-benchmarks.md`.

- [ ] **Step 1:** `BENCHMARKS.md` — the deliverable. Sections:
  - **Methodology**: host spec (CPU/RAM), stack topology (co-located, single Postgres multi-db, replica counts), tool (Gatling), load shape (ramp→hold), the co-located-is-relative-not-absolute caveat.
  - **Results table** per scenario: max sustainable RPS, p50/p95/p99 latency, error%, and the limiting resource.
  - **Bottleneck analysis** (the core value), each backed by the numbers:
    1. **Source-account lock** — quantify hot-account RPS vs cross-account RPS (the ratio shows the per-account serialization cost from BANK-11). Recommendation: shard hot accounts / async settlement / the lock is correct-but-serializing.
    2. **Derived-balance read cost** — balance/statement latency at ledger depth 10 vs 1k vs 10k (the price of no-materialization). Recommendation: revisit the materialized-balance trade-off IF read latency at depth dominates, or a snapshot/rollup; note the user's explicit no-materialization choice and when to reconsider.
    3. **Saga settle latency** — the request→COMPLETED distribution under load; where the time goes (outbox poll interval, Kafka hops, consumer concurrency). Recommendation: outbox poll tuning / partition + consumer-concurrency increase.
    4. **DB pool / single Postgres** — Hikari saturation, per-service pool sizing, the shared-instance contention. Recommendation: pool sizing, separate instances, read replicas for the read path.
    5. **Other**: gateway projection lag, Schema Registry caching, rate-limit overhead, GC.
  - **What scales horizontally vs not**: cross-account writes + reads scale with replicas (BANK-9 stateless + Redis idempotency); per-account writes are lock-bound; the saga settle is bound by consumer concurrency + partitions.
  - **Recommendations** prioritized.
- [ ] **Step 2:** ADR `0024-performance-benchmarks.md` — the benchmark approach (Gatling against the deployed stack), the chosen scenarios and WHY (each targets a known architectural pressure point), the headline findings, and the standing decisions they inform (no-materialization read cost; source-lock serialization; outbox/partition tuning). Link to BENCHMARKS.md for numbers.
- [ ] **Step 3:** `gradle build` → SUCCESSFUL (benchmarks excluded — only `gatlingRun` runs load, not `build`).
- [ ] **Step 4: commit**
```bash
git add examples/acme-bank/BENCHMARKS.md docs/decisions/0024-performance-benchmarks.md
git commit -m "docs: BENCHMARKS.md (load results + bottleneck analysis) + ADR 0024 performance benchmarks"
```

---

## Done criteria for BANK-13

- A reusable Gatling harness drives the live stack with real tokens; scenarios cover write (cross vs hot account), open, read (vs ledger depth), saga-settle, and mixed.
- A real run produced measurements (RPS, p50/p95/p99, error%) per path at the host's achievable load, with the limiting resource identified per path.
- `BENCHMARKS.md` reports the numbers + a prioritized bottleneck analysis tied to the architecture (source-lock, derived-balance, saga-settle, DB pool), framed as relative-on-this-host.
- Benchmarks are on-demand only (`gatlingRun`), excluded from `gradle build`; ADR 0024 written.
- `gradle build` green.

---

## Self-review notes

- **Coverage:** harness + token pool (T1); write/open (T2); read/settle/mixed (T3); live run + capture (T4); report + ADR (T5). All target the explicit ask: bottlenecks, measurements, sustainable traffic, e2e.
- **Type consistency:** `BenchEnv`/`TokenPool`/`Setup` shared across simulations; system-property params (`BENCH_USERS`, `BENCH_SOURCE_MODE`, `BENCH_LEDGER_DEPTH`) consistent between sims and `run-benchmarks.sh`.
- **No placeholders:** scenarios + metrics + report sections concrete.
- **Honesty:** the report MUST state co-located/single-host → relative not absolute; if the host caps the load, record the achieved numbers + limiter, don't fabricate prod-scale figures. The bottleneck RANKING is the durable deliverable.
- **Risk:** running 5 service containers + infra + Gatling on one dev host will likely be CPU-bound before the app logic saturates — which itself is a finding (note co-location as the first-order limiter, then read the per-path RELATIVE differences, e.g. hot vs cross-account, which hold regardless of absolute scale). Keep load modest and comparative. Gatling 3.13 + JDK 21 compatibility — verify the plugin version; if the gatling-gradle plugin fights the Spring/Gradle setup, fall back to k6 (a `k6` script + the same scenarios) and note the substitution.
