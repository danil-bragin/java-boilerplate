# BANK-18: capacity sweep — real max QPS Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** Measure the TRUE maximum sustainable throughput of the acme-bank stack on the current host, by removing the four measurement artifacts that capped the BANK-13/17 write number at a misleading ~5–10 req/s (per-caller rate-limit, breaker tripping on co-located latency, retry amplification, closed-loop zero-think load) and ramping each path to its real knee. Report max QPS @ p99<target, err<1% per path, the saga's sustainable drain rate, and the true limiting resource at saturation.

**Architecture:** The ~5–10 write req/s was NOT capacity — it was the gateway bucket4j rate-limit (100/min/IP, single-IP bench), the circuit breaker tripping under co-located CPU starvation, resilience4j retry amplification, and a closed-loop zero-think Gatling profile. This phase uses BENCH-ONLY overrides (env/profile, NOT permanent prod changes) to lift those protective policies for the measurement, an open-model arrival-rate load, and a ramp-to-knee, to find what the MACHINE can actually do. The protective configs stay the deployed default — we measure capacity beneath the policy, then report both ("machine does X; per-caller is rate-limited to 100/min by design").

**Tech Stack:** Gatling open-model (`constantUsersPerSec`/`rampUsersPerSec`), the BANK-9 compose stack with bench-only env overrides, `rpk group describe` for saga drain/lag, `docker stats`/Prometheus for the saturating resource.

> Follows the strong-vs-eventual + scaling work; corrects the BANK-13/17 write-capacity methodology. Builds on BANK-13..17.
> **Environment (every task):** work from `/Users/npden4ik/Projects/java-boilerplate`, on `master`; system `gradle` (8.14) NEVER `./gradlew`; JDK 21; Docker up; all images cached. Heavy live run — push to the knee but back off if the host destabilizes and record the achieved numbers + limiter. Tear down (`down -v`) after. `gradle <module>:spotlessApply` before commits.

---

## Task 1: bench-only overrides to remove the four artifacts

**Files:** `examples/acme-bank/compose.bank.yaml` (parameterize the protective knobs via env with prod-safe defaults), a bench override file `examples/acme-bank/compose.bench.yaml` OR a `.env.bench` consumed by `run-benchmarks.sh`; the gateway/transfers `application.yaml` (ensure the knobs are env-driven).

- [ ] **Step 1:** Make these protective settings ENV-DRIVEN with the CURRENT (safe) values as defaults — so prod is unchanged, but the bench can override:
  - **Rate-limit (bucket4j):** the gateway limit capacity/refill → env `RATE_LIMIT_CAPACITY` / `RATE_LIMIT_REFILL_PER_MIN` (defaults = today's 100/min). Read the acme-ratelimit config + the gateway `application.yaml` `bucket4j.filters[]` and bind the capacity/refill to env.
  - **Circuit breaker:** resilience4j `transfers`/`accounts` instances `slow-call-duration-threshold`, `failure-rate-threshold`, `slow-call-rate-threshold` → env-driven (defaults = today's). 
  - **Retry:** the `transfers`/`accounts` retry `max-attempts` → env `*_RETRY_MAX_ATTEMPTS` (default = today's).
- [ ] **Step 2:** A `compose.bench.yaml` override (or `.env.bench`) that, for the capacity run only, sets: rate-limit capacity very high (effectively off), breaker slow-call threshold high (e.g. 5s) so it doesn't trip on co-located latency, retry max-attempts=1 (no amplification). Document clearly these are MEASUREMENT overrides, not prod settings.
- [ ] **Step 3:** Verify prod defaults unchanged: `docker compose -f compose.bank.yaml config` still shows the 100/min limit + current breaker/retry when the bench override is NOT applied. `gradle build` green (no main-code behavior change — only env binding added).
- [ ] **Step 4: commit**
```bash
git add examples/acme-bank
git commit -m "bench: make rate-limit/breaker/retry env-driven (prod defaults unchanged) + compose.bench overrides for capacity measurement"
```

---

## Task 2: open-model capacity simulations (ramp to knee)

**Files:** `examples/acme-bank/benchmarks/src/gatling/java/com/acme/bank/bench/CapacitySweepSimulation.java` (+ reuse `BenchEnv`/`TokenPool`/`Setup`).

- [ ] **Step 1:** Add open-model capacity simulations (arrival-rate, ramp-to-knee), distinct from the BANK-13 closed-loop ones:
  - **Write capacity:** `rampUsersPerSec(5).to(BENCH_PEAK_RPS).during(rampSeconds)` then hold at the highest rate that keeps err<1% & p99<target. POST /v1/transfers, distinct source accounts (from a large `Setup` pool so the source lock never serializes the measurement), unique idempotency keys.
  - **Read capacity:** same open-model ramp on GET balance / GET transfer.
  - **Mixed:** 70/25/5 read/write/open at a ramped arrival rate.
- [ ] **Step 2:** Parameterize peak rate + ramp via system props (`BENCH_PEAK_RPS`, `BENCH_RAMP_SECONDS`, `BENCH_HOLD_SECONDS`). The simulation should make the knee visible (Gatling reports per-second RPS + percentiles + error%).
- [ ] **Step 3:** Compile (`gatling` source set). Commit.
```bash
gradle :examples:acme-bank:benchmarks:spotlessApply 2>/dev/null || true
git add examples/acme-bank/benchmarks
git commit -m "bench: open-model capacity-sweep simulations (arrival-rate ramp to knee)"
```

---

## Task 3: RUN the sweep — find the real knees

**Files:** results capture; `run-benchmarks.sh` updated for the capacity profile.

- [ ] **Step 1:** Bring up the stack WITH the bench overrides: `gradle bankJars && docker compose -f examples/acme-bank/compose.bank.yaml -f examples/acme-bank/compose.bench.yaml up -d --build --wait`. Confirm the overrides took (rate-limit high, breaker relaxed) and topics are 6 partitions.
- [ ] **Step 2: WRITE knee.** Ramp POST /v1/transfers arrival rate up until err>1% OR p99 knees. Record the **max sustainable accept-QPS**. SEPARATELY confirm the SAGA keeps up at that rate: watch `rpk group describe accounts transfers gateway-projection` — if consumer lag grows unbounded at the accept rate, the **sustainable** write QPS is the saga DRAIN rate (where lag stays bounded), not the accept rate. Report BOTH: peak POST-accept QPS and the lag-bounded sustainable saga QPS. Note the saturating resource (expect Postgres CPU or Redpanda `--smp=1`, NOT the breaker now).
- [ ] **Step 3: READ knee.** Ramp reads until the knee. Record max read-QPS @ p99<target, err<1%, and the saturating resource (expect Postgres CPU).
- [ ] **Step 4: MIXED knee.** Record the blended sustainable QPS.
- [ ] **Step 5:** Capture at each knee: per-service CPU (`docker stats`), Hikari active/pending, Redpanda CPU + consumer lag, GC if visible. Identify the ONE resource that caps each path.
- [ ] **Step 6:** Tear down (`down -v`).

---

## Task 4: BENCHMARKS.md capacity section + ADR + honest framing

**Files:** `examples/acme-bank/BENCHMARKS.md` (new "Capacity (BANK-18): real max QPS" section), `docs/decisions/0028-capacity-sweep.md`.

- [ ] **Step 1:** Write the REAL numbers into `BENCHMARKS.md`:
  - **Why the old ~5–10 write/s was wrong:** rate-limit (100/min/IP, single-IP bench) + breaker on co-located latency + retry amplification + closed-loop zero-think — all measurement artifacts, not capacity. Cite the overrides used to remove them.
  - **Real knees** (this host, co-located, with the four artifacts removed): max write-accept QPS, sustainable saga QPS (lag-bounded), max read QPS, mixed QPS — each with p99, err%, and the saturating resource.
  - **The two-number framing:** "the MACHINE sustains ~X transfers/s; per-caller is rate-limited to 100/min by DESIGN (a policy, not a capacity limit) — many callers aggregate to the machine limit."
  - Keep the co-located caveat: separate hosts (split Postgres, lift Redpanda `--smp=1`, service replicas) multiply this.
- [ ] **Step 2:** ADR `0028-capacity-sweep.md` — the corrected methodology (why closed-loop + protective policies understate capacity; the bench-only overrides; open-model ramp), the measured knees, the saturating resource per path, and that the protective policies remain the deployed default.
- [ ] **Step 3:** `gradle build` green (benchmarks excluded; overrides are env-only, prod defaults intact).
- [ ] **Step 4: commit**
```bash
git add examples/acme-bank/BENCHMARKS.md docs/decisions/0028-capacity-sweep.md examples/acme-bank/benchmarks examples/acme-bank/run-benchmarks.sh
git commit -m "docs(benchmarks): real capacity numbers (BANK-18 sweep) + ADR 0028 corrected methodology"
```

---

## Done criteria for BANK-18

- The four artifacts (rate-limit, breaker, retry, closed-loop) removed via BENCH-ONLY overrides; prod defaults unchanged.
- Real measured knees on this host: max write-accept QPS + sustainable saga (lag-bounded) QPS + max read QPS + mixed QPS, each with p99/err%/saturating resource.
- `BENCHMARKS.md` + ADR 0028 record the real numbers, why ~5–10 was an artifact, and the machine-vs-per-caller-policy framing.
- `gradle build` green; protective policies still the deployed default.

---

## Self-review notes

- **The crux:** ~5–10 write/s was 4 stacked artifacts, NOT capacity. The sweep removes them and ramps to the real resource limit. Expect the true write knee to be much higher and bound by Postgres CPU or Redpanda `--smp=1`, with the SUSTAINABLE rate gated by the saga drain (lag-bounded), which is the honest "writes/s the system holds."
- **Honesty:** report machine capacity separately from the per-caller rate-limit policy; keep the co-located caveat; if the host caps before a clean knee, report the achieved rate + limiter (don't extrapolate).
- **No prod weakening:** overrides are env-only via `compose.bench.yaml`; `compose.bank.yaml` defaults (100/min limit, current breaker/retry) are unchanged and verified by `compose config`.
- **Type consistency:** `BENCH_PEAK_RPS`/`BENCH_RAMP_SECONDS`/`BENCH_HOLD_SECONDS`; env `RATE_LIMIT_CAPACITY`/`*_RETRY_MAX_ATTEMPTS`/breaker thresholds; reuse `BenchEnv`/`TokenPool`/`Setup`.
- **Risk:** removing the breaker means a genuinely overloaded downstream returns raw 5xx instead of fast-failing — fine for a capacity measurement (we WANT to see the real saturation point), but watch for host instability and back off. Use a large account pool so the BANK-11 source lock isn't accidentally the thing being measured (distinct sources).
