# 0024 — Performance Benchmarks: Gatling Against the Deployed Stack, Relative Bottleneck Discovery

**Date:** 2026-06-18
**Status:** Accepted

## Context

The acme-bank stack (ADR-0020 deployable compose, ADR-0017 saga, ADR-0022 strongly-consistent
posting with a pessimistic source-account lock) had no performance characterization. We needed to
know **where it saturates first** and **which limits are intrinsic to the design vs. artifacts of the
test host** — without a production cluster to measure on. Several design choices have known but
unquantified cost: the BANK-11 per-account lock (serializes same-account writes), the derived balance
(`SUM` over `ledger_entry`, no materialized snapshot), the choreographed saga's eventual-consistency
settle time, and a single multi-database Postgres shared by all services.

The only available environment is one developer laptop running **everything co-located** (5 service
JVMs + Postgres + Redpanda + Redis + Keycloak + otel-lgtm + the load generator). Absolute throughput
on such a host is dominated by self-contention and is **not** production capacity.

## Decision

Add an on-demand **Gatling** load-test module (`examples/acme-bank/benchmarks`, `io.gatling.gradle`
plugin, `src/gatling/java`) that drives the **live** stack over the gateway's public HTTP edge with
**real Keycloak tokens**, and treat the **relative bottleneck ranking** — not absolute RPS — as the
deliverable. Benchmarks run only via `gatlingRun`; they are excluded from `gradle build`/`check` so a
Docker-less CI stays green.

**Scenarios, each targeting a specific architectural pressure point:**

- **Transfer write, cross vs. hot source** — exposes the BANK-11 source-account lock by comparing
  distinct-source (parallel) against shared-source (serialized) writes at equal load.
- **Open account** — the other write path (write tx + opening posting).
- **Read path vs. ledger depth (10 / 1 000 / 10 000)** — quantifies the no-materialization derived-
  balance cost as the ledger grows. Deep targets are seeded by direct `ledger_entry` inserts (high-
  offset ids that never collide with the app's sequence), because driving thousands of saga transfers
  is infeasible (see Consequences).
- **Saga settle** — POST→COMPLETED wall-clock, the eventual-consistency latency of the Kafka/outbox
  choreography.
- **Mixed profile** — a 70 % read / 25 % cross-transfer / 5 % open blend at a fixed arrival rate.

**Load model:** open arrival-rate for writes (controlled offered load — the right way to find a knee),
closed concurrency for reads. A `benchmarks/compose.bench-override.yaml` lifts the gateway's tight
default rate limit for the run; an orchestration script (`run-benchmarks.sh`) brings the stack up,
runs the suite, resets the breaker between aggressive runs, captures `docker stats`, and tears down.

## Headline findings

(Full numbers + tables: [`../../examples/acme-bank/BENCHMARKS.md`](../../examples/acme-bank/BENCHMARKS.md).)

1. **Edge rate limit (100 req/min/IP)** is the first ceiling any single-IP caller meets.
2. **Circuit-breaker fallback is unreachable (private methods)** — once the gateway→service breaker
   opens under burst it returns 500 and **never recovers** (half-open probes hit the same broken
   fallback). This, not the DB, is the dominant write-path limiter. **A correctness bug to fix.**
3. **Source-account lock cost is in settle, not POST RPS** — the async 202 means hot vs. cross POST
   latency is identical; on this single-host stack shared-infra contention dominates the lock. The
   lock is correct; it becomes the ceiling only for a hot account on a non-saturated multi-host system.
4. **Derived balance is cheap to ≥10 000 entries** (single-digit ms p99) thanks to the
   `(account_id, asset)` index — the no-materialization choice is validated at tested scales.
5. **Reads are bound by Postgres CPU** (~380 % under load), not the 10-connection Hikari pool; the
   single shared Postgres is the resource chokepoint.

## Consequences

- **Standing decisions informed / reaffirmed:** keep the no-materialization derived balance (ADR-0019)
  and the pessimistic source-account lock (ADR-0022) — both measured correct-and-adequate; revisit only
  at hot-account or million-entry extremes. Prioritize fixing the breaker-fallback visibility, keying
  the rate limit on the authenticated subject, splitting/replicating Postgres, and raising Redpanda
  partitions + consumer concurrency to scale settle throughput.
- **Honesty constraint:** the report states the co-located/single-host caveat up front and reports
  achieved numbers + the limiting resource per path; it does not fabricate prod-scale figures.
- **Seeding hazard recorded:** a naive `MAX(id)+n` ledger seed collided with the app's
  `ledger_entry_seq` and broke live postings; deep-ledger seeding must use a disjoint high-offset id
  range. Documented in BENCHMARKS.md.
- **On-demand only:** `gatlingRun` is never wired into `build`/`check`; a real run needs Docker + the
  full stack and is resource-heavy.
```

