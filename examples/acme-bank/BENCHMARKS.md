# acme-bank — Load Benchmarks & Bottleneck Analysis (BANK-13)

A reusable [Gatling](benchmarks/) harness drives the **live** `compose.bank.yaml` stack (all five
services + Keycloak + Redpanda + Redis + Postgres + otel-lgtm) over the gateway's public HTTP edge
with **real Keycloak tokens**, and measures throughput / latency percentiles / error rate per path.
Scenarios are designed to expose the architecture's known pressure points: the BANK-11 source-account
lock, the derived-balance SUM read (no materialization), saga settle latency, and DB-pool / single-
Postgres contention.

> **READ THIS FIRST — the numbers are RELATIVE, not production capacity.** Everything ran co-located
> on ONE laptop: 5 service JVMs + 5 infra containers + the Gatling JVM, all sharing 14 vCPU / ~8 GB of
> Docker memory. Absolute RPS is therefore a property of *this host under self-contention*, not of the
> design. The durable deliverable is the **relative bottleneck ranking and scaling behavior** (which
> path saturates first, read-vs-write, where latency concentrates), which transfers to real multi-host
> deployments. Where the host capped a run, that is stated explicitly with the achieved numbers.

---

## 1. Methodology

| Aspect | Value |
|---|---|
| Host | Apple `Mac16,7`, 14 logical CPUs, macOS 26.2; Docker Desktop 28.3 with **14 CPUs / ~8 GB** allotted |
| Toolchain | JDK 21 (Gradle foojay toolchain), Gradle 8.14, **Gatling 3.13.5** via `io.gatling.gradle` plugin |
| Stack | `examples/acme-bank/compose.bank.yaml` — 5 Spring Boot services, single Postgres (one DB per service), Redpanda (1 partition, `--smp=1`, 1 GB), Redis, Keycloak 26, otel-lgtm |
| Driver | Gatling `src/gatling/java`, real password-grant tokens (`alice`), round-robined; accounts pre-opened + funded by `Setup` |
| Load shape | closed model (ramp concurrency → hold) for reads; **open arrival-rate** model for writes (the only stable way past the breaker — see §3.1); steady-state hold 18–40 s |
| Resource signals | `docker stats` (per-container CPU/mem), `pg_stat_activity` (Postgres connections); per-service actuator `/metrics` is **not exposed** in the compose profile, so HikariCP gauges were read indirectly via Postgres |

**Rate-limit caveat (load-bearing).** The gateway ships a deliberately tight default rate limit
(bucket4j: **100 requests / minute / remote-IP**). A single-IP load generator hits that ceiling
immediately (first run: 99.95 % `429`). To measure the *downstream* app bottlenecks the benchmark
applies `benchmarks/compose.bench-override.yaml`, which lifts the gateway capacity to 100 000/min. The
default limit is itself **Finding #0** below.

**Deep-ledger seeding.** Reaching ledger depth 1 000 / 10 000 by driving real transfers is infeasible
here (the write path trips a circuit breaker above a few req/s — §3.1). Instead the deep-ledger read
targets were seeded by inserting `ledger_entry` rows **directly into Postgres**, using ids in a high
offset range (`2e9+`, `3e9+`) that the app's `ledger_entry_seq` allocator never reaches, so the live
service is uncontaminated. (An earlier naive `MAX(id)+n` seed *did* collide with the sequence and was
backed out — noted here as a seeding hazard.)

---

## 2. Results

All latencies in **ms**; "OK" = expected status (201/202/200). Writes used the open arrival-rate model;
reads/saga used closed concurrency. Each cell is a steady-state hold.

### 2.1 Write path — transfer POST (`POST /v1/transfers`, async 202)

| Scenario | Offered | OK rps | p50 | p95 | p99 | error % | Limiter |
|---|---|---|---|---|---|---|---|
| cross-account (distinct sources) | 3 req/s | ~2.8 | 19 | 238 | 434 | 4.8 % (503) | gateway→transfers **circuit breaker** trips on the first 5xx burst |
| hot-account (one shared source) | 3 req/s | ~2.9 | 18 | 30 | 439 | 3.8 % (503) | same breaker |
| 20 concurrent (closed, zero-think) | — | n/a | — | — | — | **91–100 %** (503/429) | breaker fully open after the first burst; never recovers without a restart |

The `POST /v1/transfers` returns **202 in ~18–19 ms** at the gateway — it only publishes the
`transfer-requested` event; the money movement is the asynchronous saga (§2.3). At the POST layer,
**hot and cross are indistinguishable** (p50 18 vs 19 ms) precisely *because* the source lock lives in
the downstream saga, not in the synchronous POST — see §3.2.

### 2.2 Read path — derived balance vs ledger depth

`GET /v1/accounts/{id}/balance` is a `SUM(amount)` over `ledger_entry` with **no materialized
balance**; `GET .../statement` is a paged ledger read. Run at 4 closed users (≈2 000 rps offered).

| Ledger depth | balance p50 | balance p95 | balance p99 | statement p50 | statement p99 | OK rps (per leg) | error % |
|---|---|---|---|---|---|---|---|
| **10** | 2 | 2 | 3 | 2 | 4 | ~690 | 0 % |
| **1 000** | 2 | 5 | 8 | 3 | 10 | ~615 | 0 % |
| **10 000** | 3 | 4 | 5 | 4 | 7 | ~515 | 0 % |

The derived-balance SUM stays **single-digit ms even at 10 000 entries** — the
`idx_ledger_entry_account (account_id, asset)` index turns the aggregate into an efficient index scan.
Per-leg throughput erodes modestly (~690 → ~515 rps) as depth grows. Reads sustain **>2 000 rps** with
**zero errors** at 4 users.

### 2.3 Saga settle latency (POST → COMPLETED, end-to-end wall-clock)

Measured directly (POST, then poll `GET /v1/transfers/{id}` at 100 ms until terminal):

| Load | settle min | settle median | settle max | terminal |
|---|---|---|---|---|
| Sequential, 1 at a time (5 samples) | 310 | **~325** | 327 | COMPLETED |
| 6 concurrent, cross-account | 483 | 488 | 489 | 6/6 COMPLETED |
| 6 concurrent, hot-account | 142 | **144** | 147 | 5/6 COMPLETED (1 lost) |

The full choreographed saga (`transfers → Kafka → antifraud → Kafka → accounts(post) → Kafka →
transfers`) settles in **~325 ms** under light load. The Gatling `saga-settle` group is retained in the
harness but reports cumulative *request* time, not wall-clock; the wall-clock figures above are the
authoritative settle numbers.

### 2.4 Resource signals during steady state

`docker stats` during the depth-10 000 read hold (10 users):

| Container | CPU % | Mem |
|---|---|---|
| **postgres** | **380 %** | 143 MB |
| gateway | 174 % | 974 MB |
| accounts | 163 % | 783 MB |
| observability (otel-lgtm) | 1–7 % | 0.9–1.8 GB |
| transfers / antifraud / redpanda / redis / keycloak | <3 % each | 0.2–0.7 GB |

Idle memory already sits at **~6 GB of the ~8 GB** Docker allotment (otel-lgtm alone ~1.8 GB), leaving
little headroom. `pg_stat_activity` during read load: each service holds a **10-connection** Hikari
pool (Spring default); accounts peaked at **6 of 10 active** — the pool was *not* the limiter, Postgres
**CPU** was.

---

## 3. Bottleneck analysis (prioritized)

### Finding #0 — Gateway rate limit is the first-order throttle (edge)

The default **100 req/min/IP** bucket4j limit caps *any* single-source caller far below app capacity
(observed: 99.95 % `429` on the first unthrottled-by-mistake run). This is correct production hygiene
(per-client fairness) but means the gateway, not the bank logic, is the first ceiling a load test or a
busy single client meets. **Recommendation:** keep it for untrusted clients; key it on the
authenticated subject (not raw remote-IP) so a shared NAT/proxy isn't collectively throttled; raise or
exempt it for trusted internal callers. *(The benchmark lifts it via the override to see past it.)*

### Finding #1 — Circuit-breaker fallback is unreachable → writes collapse under burst (CRITICAL)

The single dominant write-path limiter is **not** the database or Kafka — it is the gateway's
resilience4j circuit breaker on the synchronous `transfers`/`accounts` calls. Two compounding issues:

1. The breaker (sliding-window 10, 50 % failure threshold) opens after a brief 5xx burst — expected.
2. **The `@CircuitBreaker` fallback methods on `RestTransfersClient` are `private`**, so when the
   breaker is open resilience4j's reflective `FallbackMethod` throws
   `IllegalAccessException → UndeclaredThrowableException`, returning **HTTP 500** instead of a graceful
   degraded response. Worse, the **half-open probe calls hit the same broken fallback and re-open the
   breaker**, so once tripped it **never recovers** — only a gateway restart clears it.

Symptoms measured: at 20 concurrent zero-think transfers, 91–100 % `503`/`500`; even 2 concurrent
zero-think users eventually wedged the breaker open; the n=10 concurrent settle probe left a transfer
stuck non-terminal (infinite). Below ~3 req/s offered, the write path is healthy (95–96 % OK, p50
~19 ms). **Recommendations (in priority order):** (a) make the fallback methods accessible
(package-private/public) so degradation works and half-open can recover — this is a correctness bug,
not a tuning knob; (b) widen the sliding window / raise the open-state grace so transient co-located
spikes don't trip it; (c) the async 202 design is right — consider shedding load by queue depth rather
than a synchronous breaker on the hot publish path.

### Finding #2 — Source-account lock (BANK-11): the cost is in settle, not POST RPS

The pessimistic source-account lock (ADR-0022) serializes postings on the **same** source account. The
benchmark's important nuance: because `POST /v1/transfers` is **asynchronous** (publishes an event and
returns 202), the lock does *not* show up as POST latency — hot and cross POST p50 are identical
(18 vs 19 ms). The lock surfaces in the **saga settle distribution**:

- 6 concurrent **cross-account** transfers settled in ~488 ms (parallel postings, but contending on the
  *shared* Postgres/Kafka/CPU of this one host).
- 6 concurrent **hot-account** transfers settled in ~144 ms median **but lost 1 of 6** — the same-row
  lock serializes them into a tight warm-cache sequence, while the surrounding burst tripped the
  breaker and dropped one.

So on this single-host stack the *infrastructure* contention (one Postgres, one-partition Redpanda)
dominates the per-account lock cost; the lock is **correct and not the first-order limiter here**. In a
real deployment with horizontal service replicas and a non-saturated DB, the per-account lock becomes
the ceiling for **writes to one hot account** (they cannot parallelize), whereas cross-account writes
scale out. **Recommendation:** the lock is correct (it prevents overdraw); if a single account becomes
a write hotspot, shard its postings (sub-accounts) or move to an append-only command log per account
rather than a row lock.

### Finding #3 — Derived-balance read cost (no materialization): cheap to ≥10 k entries

The no-materialization choice (balance = `SUM(ledger_entry.amount)`) costs **single-digit ms even at
10 000 entries** thanks to the `(account_id, asset)` covering index — p99 stayed 3 → 8 → 5 ms across
depth 10 / 1 000 / 10 000, and reads sustained >2 000 rps with zero errors. **The no-materialization
trade-off is validated at these depths.** **Recommendation:** keep it. Revisit only if a single account
reaches *millions* of entries (then a periodic balance snapshot / rollup with incremental deltas pays
off) or if a covering index can no longer stay in cache. Reads are the most horizontally scalable path
(stateless services + Redis idempotency + read-replica-friendly SUM).

### Finding #4 — DB pool & single Postgres: the real resource ceiling for reads

Under read load, **Postgres CPU hit ~380 %** (≈4 cores) while the 10-connection Hikari pools were only
~60 % utilized — the limiter is **Postgres compute**, not pool size, on this single shared instance
(one process serving all five service databases). **Recommendations:** (a) the single multi-DB Postgres
is fine for dev but is the shared chokepoint — give the read-heavy `accounts` DB its own instance and a
read replica in production; (b) the default 10-connection pool was adequate here; size it per service
against the target DB once instances are split; (c) PgBouncer in front if connection counts grow with
replica count.

### Finding #5 — Other

- **Co-location is the first-order limiter for absolute numbers.** Idle memory ~6/8 GB; otel-lgtm alone
  ~1.8 GB. Under load Postgres + gateway + accounts together exceed the host's parallelism, so
  everything throttles before any single service's app logic saturates. On a real multi-host
  deployment the per-path *relative* findings above hold; the absolute RPS will be much higher.
- **Notifications service is down** in this stack (a pre-existing config bug: it expects
  `schema.registry.url` which the compose does not map to its consumer) — it is a downstream event
  consumer off the money-movement critical path, so the saga still completes. Flagged, not fixed here.
- **Single-partition Redpanda** (`--smp=1`, one partition per topic) bounds saga consumer parallelism —
  raise partitions + consumer concurrency to scale the settle throughput in production.

---

## 4. What scales horizontally vs not

| Path | Scales with replicas? | Bound by |
|---|---|---|
| Reads (balance/statement/projection) | **Yes** — stateless services, Redis idempotency, indexed SUM | Postgres CPU / read replicas |
| Cross-account writes | **Yes** — distinct source rows post in parallel | DB write capacity, Kafka partitions, the breaker (once fixed) |
| **Per-(hot-)account writes** | **No** — serialized by the source-account lock by design | the pessimistic lock (correct); shard the account to scale |
| Saga settle throughput | Partly — bound by Kafka partitions + consumer concurrency | 1-partition Redpanda here; raise partitions + concurrency |
| Synchronous gateway→service hop | Replicas help, but the **broken-fallback breaker caps it first** | Finding #1 (fix first) |

---

## 5. Prioritized recommendations

1. **Fix the circuit-breaker fallback visibility** (Finding #1) — it is a correctness bug that turns a
   transient spike into a permanent wedge. Highest priority.
2. **Key the rate limit on the authenticated subject** and raise/exempt it for trusted internal callers
   (Finding #0).
3. **Split the shared Postgres** (at least the read-heavy `accounts` DB) and add a read replica before
   scaling read traffic (Finding #4).
4. **Raise Redpanda partitions + consumer concurrency** to scale saga settle throughput (Finding #5).
5. **Keep** the no-materialization balance and the source-account lock — both are validated as
   correct-and-adequate at the tested scales (Findings #2, #3); revisit only at hot-account or
   million-entry extremes.

---

## 6. Reproducing

```bash
# Build jars + bring the stack up with the rate-limit override, run the suite, capture stats, tear down:
examples/acme-bank/benchmarks/run-benchmarks.sh all

# Or piecewise:
examples/acme-bank/benchmarks/run-benchmarks.sh up
examples/acme-bank/benchmarks/run-benchmarks.sh run TransferWriteSimulation -DBENCH_SOURCE_MODE=hot -DBENCH_ARRIVAL_RATE=3
examples/acme-bank/benchmarks/run-benchmarks.sh reset      # reset the breaker between aggressive runs
examples/acme-bank/benchmarks/run-benchmarks.sh run ReadPathSimulation -DBENCH_LEDGER_DEPTH=10 -DBENCH_USERS=4
examples/acme-bank/benchmarks/run-benchmarks.sh down
```

Load knobs (system properties / env, see `BenchEnv`): `BENCH_USERS`, `BENCH_ARRIVAL_RATE`,
`BENCH_RAMP_SECONDS`, `BENCH_HOLD_SECONDS`, `BENCH_RATE`, `BENCH_SOURCE_MODE` (`cross|hot`),
`BENCH_LEDGER_DEPTH`, `BENCH_READ_TARGET` (pre-seeded account id for deep-ledger reads),
`BENCH_POOL_SIZE`, `BENCH_TOKEN_COUNT`. Benchmarks are **on-demand only** (`gatlingRun`); they are NOT
part of `gradle build`.
