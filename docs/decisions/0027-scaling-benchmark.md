# 0027 — Scaling Benchmark: Measured Outcome of BANK-15/16 (Settle Delta; Honest Per-Account Caveat)

**Date:** 2026-06-18
**Status:** Accepted

## Context

ADR-0025 (BANK-15) made `posting-requested` multi-partition and source-account-keyed
(one writer lane per account) with per-partition consumer concurrency. ADR-0026
(BANK-16) generalized that to **every** saga topic — all provisioned to 6 partitions
by every service that produces or consumes them, per-service listener `concurrency: 6`,
plus Kafka latency knobs (`linger.ms` / `fetch-max-wait` / `max-poll`) — all keyed to
preserve per-entity ordering, with no money-safety surface touched (`acks=all` +
idempotent producer + BANK-11 source lock + posting-PK anchor + inbox dedup retained).

Those ADRs were correctness- and design-driven. BANK-17 asks the empirical question:
**on the same co-located single host and the same BANK-13 load levels, what actually
moved?** The BANK-13 baseline (`BENCHMARKS.md` §2) is the "before". The hard
requirement is honesty: a single host with one Postgres and one `--smp=1` Redpanda
cannot exhibit the multi-host gains some of these changes target, so the ADR must say
plainly what the host could and could not show.

## Decision

Record the measured before/after (`BENCHMARKS.md` §7) as the accepted outcome of
BANK-15/16, and the honest assessment of what each change bought on this host.

### What was verified

- **Deployed topics are genuinely 6 partitions** — `rpk topic list` shows all seven
  saga topics at 6; `rpk group describe accounts` shows **6 consumer members, one per
  `posting-requested` partition, TOTAL-LAG 0** (and the group drains back to lag 0 after
  load). The BANK-15/16 provisioning took effect at deploy. This was the gating check —
  had they been 1 partition, the comparison would have been meaningless.

### What moved

- **Saga-settle (cross-account) is the real, large gain.** Wall-clock POST→COMPLETED:
  - Sequential: ~325 ms → **~188 ms** (−42 %, from the BANK-16 latency knobs shortening
    each saga hop).
  - **6 concurrent cross-account: 488 ms → 122 ms (−75 %).** Six distinct-source
    transfers that the BANK-13 single-partition funnel serialized onto one consumer lane
    now fan out across 6 partitions / 6 threads.
  - 12 concurrent cross-account: **183 ms, 12/12** — under the old *6-concurrent* number.
- **Write-POST tails tightened** (p99 ~434→26 / ~439→20 ms; error ~4–5 %→0 % at 3 req/s)
  from the producer latency tuning + the breaker no longer flickering at that rate.

### What did NOT move on this host (and why)

- **Hot-account settle is intentionally flat** (~150–161 ms): same-source postings
  serialize on the single-writer lane by design (BANK-15). More partitions don't help one
  hot account — and must not.
- **No full-stack per-account write RPS jump** was observable, and none is claimed. The
  per-account single-writer change **raises the per-account ceiling and removes lock-wait
  overhead**, but those pay off only at **multi-host scale with a non-saturated DB**. On
  one co-located Postgres the infrastructure caps the write path before the per-account
  lock binds. The correctness + single-writer guarantee is proven by the BANK-15 tests
  **`ConcurrentDebitIT`** and **`ConcurrentPostingConsumerIT`**, not by a benchmark.
- **Reads unchanged** (p50 2 / p99 6 ms, ~1 390 rps, 0 % err) — they don't touch the
  saga-consumer path, so BANK-15/16 was not expected to move them.

### The resource now capping each path

| Path | Limiter after BANK-15/16 |
|---|---|
| Saga settle (cross) | **No longer the consumer parallelism** — consumer lag stayed 0 to ~10 POST/s. The **synchronous POST → transfers hop + gateway circuit breaker** knees first (503s begin ~10/s, ~5/s sustained). |
| Write POST | Same front-door breaker / single-host downstream saturation (BANK-13 Finding #1, unchanged). |
| Per-(hot-)account write | The single-writer lane **by design** (correct); shard the account to scale further. |
| Reads | **Postgres CPU** on the single shared instance (BANK-13 Finding #4, unchanged). |

### The next lever (beyond the application layer)

BANK-15/16 exhausted the application-layer scaling knobs (partitions, consumer
concurrency, producer/consumer latency tuning, single-writer keying). The remaining
ceilings are **infrastructure**, not code:

1. **Split the shared Postgres** — give the read-heavy `accounts` DB its own instance +
   a read replica; the single multi-DB Postgres is the shared chokepoint for reads and
   the write-path settle floor.
2. **More Redpanda brokers / lift `--smp=1`** — the single-core broker bounds absolute
   saga throughput even with 6 partitions.
3. **Horizontal service replicas + raise/tune the breaker** for a non-co-located
   downstream — only then does the cross-account write path scale out and the per-account
   ceiling-raise become a measurable RPS number.

## Consequences

- The settle delta is documented and reproducible (`run-benchmarks.sh` + the
  `SagaSettleSimulation` / concurrent-settle probe); the per-account change is framed as
  a ceiling-raiser + correctness win rather than a fabricated single-host speedup.
- The benchmark caveat stands: **co-located single host → relative, not absolute.** The
  durable result is the *relative* settle parallelism gain and the *moved* limiter, both
  of which transfer to a real multi-host deployment.
- See `examples/acme-bank/BENCHMARKS.md` §7 for the full before/after tables and the
  verified partition counts.
