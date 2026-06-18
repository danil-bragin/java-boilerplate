# 0029 — Write-throughput tuning: load the hardware, money-safe, no sharding (BANK-19b)

**Date:** 2026-06-19
**Status:** Accepted

## Context

BANK-18 (ADR-0028, `BENCHMARKS.md` §8) measured the real write-accept ceiling at **~550
transfers/s on this co-located host, bound by Postgres CPU** (~3.5 cores at PEAK 700/s).
The directive for BANK-19b: the hardware is capable of far more — drive write throughput
toward **2,000 transfers/s** by actually saturating the fast CPU/RAM/disk, **without
sharding** and **without weakening strong consistency** (no overdraft, Σ=0, idempotency,
`acks=all`, `synchronous_commit=on` for the money databases). The stack under-used the box:
stock Postgres config, Redpanda pinned to 1 core, Boot-default Hikari pools, 100 % trace
sampling, and ~11 DB commits per transfer across the saga.

## Diagnosis (drove the tuning)

Under a steady ~400/s write load with `pg_stat_statements`:

- **The #1 Postgres cost, by ~7×, was the antifraud velocity check** `SELECT count(*) FROM
  screening_decision WHERE source_account_id=? AND approved` — mean **0.594 ms**, 20–30×
  slower than every other statement, because `screening_decision` had **only a PK index on
  `transfer_id`** and the count **seq-scanned the whole table** every screen. (The bench's
  64-account pool of repeated `amount=1.00` trips the velocity rule, so ~99 % of bench
  transfers are *rejected at screening* — the BANK-18 "~550/s, Postgres-bound" number was
  dominated by this seq-scan on the **accept+screen** path, not money posting.)
- The rest is **per-transfer commit amplification**: ~11 commits/transfer, with Spring-
  Modulith `event_publication` insert+complete (~5) and `processed_messages` inbox inserts
  (~9–10) — each cheap but high-volume.
- Stock config gaps: `shared_buffers=128MB`, `max_wal_size=1GB` (checkpoint-storm risk),
  `commit_delay=0` (no group commit, one fsync per commit), `wal_compression=off`, Hikari
  pools = 10/service, trace sampling 100 %.
- **The Docker VM is the first-order cap: 14 CPUs / 7.653 GiB**, while the host is 14 cores /
  **48 GB** — Docker Desktop allocates only ~16 % of host RAM. RAM is zero-sum across the
  JVMs; raising the VM allocation is the **user-controlled** prerequisite to going past ~1.4k.

## Decision

Apply **money-safe** infra + commit-amplification tuning, re-bench to the new knee, and
record it. The protective/durability guarantees are unchanged.

### Levers

1. **Index the velocity seq-scan** — partial index `screening_decision(source_account_id)
   WHERE approved` (Postgres) / `(source_account_id, approved)` (Oracle). Turns the count
   into an Index-Only Scan. **Money-safe read-path optimization, no semantic change.**
2. **Postgres for the hardware** (mounted `postgresql.tuning.conf`): `shared_buffers` 256 MB,
   `effective_cache_size` 2 GB, bounded `work_mem` 16 MB, `max_wal_size` 4 GB / `min_wal_size`
   1 GB / `checkpoint_completion_target` 0.9, `wal_buffers` 64 MB, `wal_compression on`,
   `max_connections` 200, `shared_preload_libraries=pg_stat_statements`. Postgres `mem_limit`
   512 → 768 m (funded by trimming observability 1280 → 768 m + keycloak 640 → 512 m).
   **`listen_addresses='*'` is set explicitly** — `-c config_file=` replaces the whole
   `postgresql.conf`, so the image's `listen_addresses='*'` is lost and Postgres would bind
   only the loopback (services get "connection refused"); this is now load-bearing in the conf.
3. **Group commit (the money-safe fsync lever)**: `commit_delay=80µs` + `commit_siblings=5` —
   batches WAL fsyncs across concurrent commits **without** weakening durability (each commit is
   still durably flushed, just grouped). This is how the ~11-fsync/transfer cost is cut —
   **NOT** by lowering `synchronous_commit` on the money path.
4. **Redpanda `--smp` 1 → 2** (+ `--memory` 1 → 1.5 G) — the saga relay was the next ceiling
   after Postgres.
5. **Hikari pools**: hot path (transfers/accounts/gateway) 24, others 16, Σ = 104 ≤
   `max_connections` 200.
6. **Batch listeners on the idempotent NON-money hops only** — antifraud screening + the 4
   gateway-projection listeners process a poll of N records in one transaction (amortized
   commit/fsync). The **accounts POSTING consumer is NOT batched** — it keeps per-posting
   transactions + the BANK-11 source lock + Σ = 0; its fsync cost is handled by group commit.
7. **Per-database `synchronous_commit=off` — gateway projection ONLY** (datasource
   `connection-init-sql`). The `transfer_view` is a rebuildable CQRS read model (no balances,
   no money invariants, re-consumes from `earliest` on loss), so it does not need durable-on-
   commit fsync. **Money DBs keep `synchronous_commit=on`** (verified `SHOW synchronous_commit`).
8. **Trace sampling 1.0 → 0.1** — removes ~15 spans/transfer of OTLP export (observability was
   60–100 % CPU); env-overridable.

### Money-safety boundary (non-negotiable, honored)

- `synchronous_commit=on` for accounts/transfers/antifraud/notifications; `off` only for the
  rebuildable gateway projection.
- `acks=all` + idempotent producer retained. Group commit, not `sync_commit`/`acks` weakening,
  cuts the money-path fsync cost.
- Batch only where partial failure has no money meaning (screening, projection); the posting
  path stays per-posting + source lock + Σ = 0. **No sharding.**

## Results (open-model write ramp, bench overrides, this host)

| PEAK (req/s) | mean (req/s) | p99 | err % | saga lag | binding resource |
|---|---|---|---|---|---|
| 700  | 584  | 274 ms | 0 % | ≈ 0 | gateway CPU ~2.1 cores; **Postgres only ~1.5 cores** |
| 1000 | 839  | 204 ms | 0 % | ≈ 0 | gateway CPU ~2.8 cores |
| **1400** | **1,160** | **186 ms** | **0 %** | **≈ 0** | **gateway CPU ~3 cores (clean knee)** |
| 1800 | 1,480 | ~1,800 ms | 0 % (6.7 % > 1.2 s) | projection lag rising | gateway CPU saturated — past knee |

- **Clean write-accept knee ≈ 1,400 transfers/s** (p99 186 ms, 0 % error, saga lag ≈ 0) — **up
  from ~550/s, a ~2.5× gain.**
- **The bottleneck moved off Postgres** (≈3.5 → ≈1.5 cores; the indexed velocity query is now an
  Index-Only Scan and no checkpoint storms — `checkpoints_timed=0`) **onto gateway CPU** (3 cores
  maxed) with **transfers at its RAM `mem_limit`** as co-limiter.
- **The saga keeps pace**: `accounts`/`transfers` consumer-group lag stayed ≈ 0 through 1800/s
  offered; only the rebuildable gateway projection began to lag at 1800 (not money).

### Honest distance to 2k

**2k was not reached and is not claimed (~70 % of target on this box).** The hard limiter is
**gateway CPU (3 of 14 cores, saturated) + the transfers JVM at its RAM `mem_limit`**, both gated
by the **7.65 GiB Docker VM allocation**. To approach 2k on this hardware, in order: **(1) raise
the Docker Desktop RAM allocation** (user-controlled, the #1 unlock); **(2) run gateway replicas**
(the binding hop is stateless and scales horizontally); **(3) give transfers more heap/CPU**. None
is sharding; none weakens durability.

## Consequences

- Bug fixed (surfaced by batching): `JdbcInbox` exception-driven dedup poisoned a batch
  transaction on Postgres (SQLSTATE 25P02). Replaced with a conflict-ignoring upsert
  (`ON CONFLICT DO NOTHING` / Oracle `MERGE`) — idempotency unchanged, no tx poison. This also
  removes exception-driven control flow from the money-path inbox.
- `BENCHMARKS.md` §9 records the diagnosis, levers, new knee, and the honest 2k gap.
- Money-safety + saga ITs green; `ConcurrentDebitIT` (overdraft gate) green; `gradle build` green.
- Deployed defaults: the tuning is the new deployed config (it is durability-preserving); the
  bench-only overrides (rate-limit/breaker/retry) from ADR-0028 remain bench-only.
- Future work to push past ~1.4k: raise the VM, then gateway replicas — not sharding.
