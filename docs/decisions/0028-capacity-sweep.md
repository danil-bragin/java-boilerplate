# 0028 — Capacity Sweep: Real Max QPS and the Corrected Methodology (BANK-18)

**Date:** 2026-06-19
**Status:** Accepted

## Context

BANK-13/17 (`BENCHMARKS.md` §2–§7, ADR-0024/0027) reported the acme-bank write path at
a **~5–10 req/s** ceiling on the co-located dev host. That number was used as a stand-in
for capacity, but it was an **artifact of the measurement method**, not what the machine
can do. Four protective mechanisms and one load-model choice stacked to produce it:

1. **Per-caller rate limit (bucket4j, 100 req/min/IP, keyed on `getRemoteAddr()`).** A
   single-IP load generator is throttled to ~1.6 req/s. Critically, the **`transfers`
   service carries the *same* limit**, and because the gateway is a single source IP to
   transfers, *all* gateway→transfers traffic shares ONE bucket — re-imposing ~1.6/s
   *behind* the gateway. The downstream `429` surfaces as a gateway `503`, so it read as
   a capacity wall.
2. **Circuit breaker** (resilience4j `transfers`/`accounts`) opening under co-located CPU
   starvation and fast-failing via the unavailable-fallback (503).
3. **Retry amplification** (`max-attempts=3`) tripling offered load on a saturated
   downstream.
4. **Closed-loop zero-think Gatling profile** that collapses past the first 503.

The hard requirement for BANK-18 was honesty: measure the **machine** capacity beneath
the per-caller policy, **without weakening the deployed defaults**, and report it
separately from the policy.

## Decision

Adopt an **open-model, ramp-to-knee capacity methodology** with **bench-only env
overrides**, and record the measured knees (`BENCHMARKS.md` §8) as the accepted real
capacity of the stack on this host. The protective policies (100/min limit, breaker
50 % / 60 s, retry 3) **remain the deployed default**.

### Corrected methodology

- **Bench-only overrides, prod defaults intact.** The four protective knobs are now
  env-bound in the service `application.yaml` with **today's values as defaults**:
  `RATE_LIMIT_CAPACITY` (gateway *and* transfers), `*_CB_SLOW_CALL_DURATION_THRESHOLD`
  /`*_CB_SLOW_CALL_RATE_THRESHOLD`/`*_CB_FAILURE_RATE_THRESHOLD`/`*_CB_MIN_CALLS`, and
  `*_RETRY_MAX_ATTEMPTS`. `examples/acme-bank/compose.bench.yaml` overrides them for the
  measurement window only. Verified: `docker compose -f compose.bank.yaml config`
  (without the override) still resolves to 100/min + breaker 50 %/60 s + retry 3.
- **Open arrival-rate load.** `CapacitySweepSimulation` injects
  `rampUsersPerSec(start).to(PEAK)` then holds — offered load is independent of how fast
  the system answers, the only stable way to find a saturation knee (a closed model
  collapses past the first error).
- **Distinct sources.** A large pool (64) of distinct source accounts so the BANK-11
  per-account source lock is never what's measured — we want machine capacity across
  distinct accounts.
- **Saga drain check.** During each write run, `rpk group describe accounts transfers
  gateway-projection-*` is watched: if consumer lag grows unbounded at the accept rate,
  the sustainable write rate is the saga **drain** rate, not the accept rate.

### Measured knees (this host, co-located, artifacts removed; 0 % error throughout)

| Path | Real knee | p99 | saturating resource | saga lag |
|---|---|---|---|---|
| **Write accept** | **≈ 550 req/s** (sustained mean at PEAK 700) | 55–68 ms | **Postgres CPU** (~3.5 cores at 700/s) | **≈ 0** (transient ≤ 9, drains) |
| **Sustainable saga** | **= the accept rate** (lag stayed bounded at every rate) | — | saga keeps pace (6-partition drain) | bounded |
| **Read** | **≈ 466 req/s clean** (p99 8 ms); p99 knee ~900 req/s (p99 ~800 ms) | 8 ms clean | **gateway + accounts CPU** (~190 % each at the knee) | n/a |
| **Mixed (70/25/5)** | **≈ 130 req/s** sustainable; **OOM at ~300 req/s** | 11 ms | **gateway CPU + RAM** (heap OOM-killed at 300/s) | ≈ 0 |

### Key findings

- **The breaker is NOT the limiter once relaxed.** Every write/read run held **0 %
  error**; the saturating resource was Postgres CPU (write) or gateway/accounts CPU
  (read), never `CallNotPermittedException`. The old 503 wall was the **downstream
  transfers rate limit** (artifact #1) rendered as 503 — not the breaker, not capacity.
- **The saga is no longer a throughput bottleneck.** After BANK-15/16 the 6-partition
  saga drains in real time up to the write-accept ceiling (lag ≈ 0 at 700/s offered), so
  the sustainable write rate equals the accept rate, gated by Postgres CPU.
- **Machine vs. per-caller policy.** The machine sustains ≈ 550 transfers/s and
  ≈ 460–900 reads/s on this one host; a single caller is rate-limited to 100 req/min
  (~1.6/s) **by design** — a policy, not a capacity limit. Many distinct callers
  aggregate to the machine ceiling.

### Co-located caveat

One host, 14 vCPU / ~8 GB shared across 5 service JVMs + 5 infra containers + Gatling.
The write ceiling is one **shared Postgres CPU**; read/mixed is **gateway + accounts CPU
/ RAM**; Redpanda `--smp=1` was never the binding resource (~50–60 %). Splitting Postgres
/ adding cores, lifting Redpanda's SMP, and running gateway/service replicas each
multiply the corresponding number. The durable deliverable is the **per-path limiter**
and that **the saga keeps pace** — not the absolute QPS.

## Consequences

- `BENCHMARKS.md` §8 and this ADR record the real capacity, the why-5-10-was-wrong
  explanation, and the machine-vs-per-caller framing.
- The protective policies remain the deployed default; only env bindings were added (no
  main-code behavior change). `gradle build` stays green.
- Future capacity work should split Postgres and add a per-IP-distinct (or per-key)
  rate-limit bypass in the bench rather than env-raising capacity, to push past the
  single-Postgres-CPU write ceiling.
