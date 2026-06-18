# 0030 — Gateway horizontal scaling behind an nginx LB → 2k write/s reached (BANK-20)

**Date:** 2026-06-19
**Status:** Accepted

## Context

BANK-19b (ADR-0029, `BENCHMARKS.md` §9) lifted the write-accept knee to **~1,400 transfers/s**
and identified the new binding resource as **single-gateway CPU (3 cores maxed) + transfers RAM**,
both gated by the **7.65 GiB Docker VM allocation** — flagging two user/operator levers to go
past ~1.4k toward the 2,000/s target: **(1) raise the Docker VM RAM** (the #1 unlock), then
**(2) run gateway replicas** (the binding hop is stateless and scales horizontally). Neither is
sharding; neither weakens money durability.

For BANK-20 the user raised the Docker VM **7.65 → ~23.68 GiB** (confirmed `docker info`: 14 CPUs /
23.68 GiB), removing the RAM starvation. This ADR records lever (2): scale the gateway out.

## Decision

Run the **stateless gateway as N replicas behind an nginx round-robin load balancer**, rebalance
the per-service resources for the 24 GiB VM, and re-bench the write sweep through the LB to prove
the knee. The money-safety boundary is unchanged.

### Why the gateway is the horizontal lever (and why it is money-safe)

The gateway holds **no money logic** — it validates the JWT, enforces idempotency, proxies to the
downstream saga, and projects the `transfer_view` read model. It is **stateless across replicas**:

- **Idempotency is in shared Redis** (`RedisIdempotencyStore`) — a repeated `Idempotency-Key` to a
  *different* replica replays the same 202, never double-submits.
- **The `transfer_view` projection is in shared Postgres**, and the projection consumers distribute
  across replicas via their consumer group; the **inbox dedup keyed by `(listener, messageId)`**
  (shared Postgres) makes a record seen by two replicas during a rebalance idempotent.
- The downstream money path (accounts posting, the BANK-11 source lock, Σ=0) is **per-partition
  single-writer** and untouched by replicating the edge.

So N replicas multiply the edge CPU ceiling without touching correctness.

### Changes

1. **nginx LB** (`nginx/gateway.conf`, the only published edge, host `8080:80`) round-robining to
   `gateway:8080`; passes `Authorization` + `Idempotency-Key` verbatim, sets `X-Forwarded-For` so
   each replica still keys its per-caller rate limit on the real client IP. The gateway host port is
   removed; the gateway runs via `docker compose --scale gateway=3`.
2. **Resource rebalance for 24 GiB** (`compose.bank.yaml`, `db/postgresql.tuning.conf`): Postgres
   `mem_limit` 768m → 5g + `shared_buffers` 256MB → 3GB (`effective_cache_size` → 8GB) +
   `max_connections` 200 → 300 (Σ pools × 3 gateway replicas + headroom); Redpanda `--smp` 2 → 4,
   `--memory` → 4G; transfers/accounts `mem_limit` 768m → 2g; gateway 1.5g × 3; observability → 1g.
   Σ ≈ 22.6 GiB within the VM. **WAL/checkpoint/group-commit + `synchronous_commit=on` for money DBs
   are unchanged.**

### nginx connection-reuse (load-bearing, found during the re-bench)

The first config resolved the upstream via a per-request `proxy_pass http://$variable`, which cannot
keep upstream keepalive — a fresh TCP connection per request. At 1800 offered that collapsed to
~1,000 rps / 20 % `upstream timed out` errors **with the gateways only ~16 % CPU** (connection
churn, not CPU, was the ceiling). Switching to a static `upstream { server gateway:8080; keepalive
512; }` (Docker DNS expands the service name to all `--scale` replica IPs; HTTP/1.1 + empty
`Connection` engages the pool) took the same 1800 to **0 % error**. Trade-off: a static upstream
resolves at startup, so a mid-run `--scale` change needs an nginx recreate — fine for a fixed-N
bench; dynamic membership in prod wants NGINX-Plus `resolve` or a service mesh.

## Replica-correctness verification (live, 3 replicas behind nginx)

- **Idempotency across replicas:** 10 identical-`Idempotency-Key` POSTs through the LB returned the
  **same** `transferId` (202 each); the DB held **exactly 1** `transfer` row — shared Redis replays
  across replicas, no double-submit.
- **Projection across replicas:** **1** `transfer_view` row (COMPLETED); repeated LB GETs (different
  replicas, shared Postgres) all returned the same view — no double-processing. Balances Σ=0.
- **Happy path through the LB:** open accounts → transfer → REQUESTED → APPROVED → COMPLETED in ~2 s;
  balances moved correctly.

## Results (open-model write ramp, bench overrides, 24 GiB VM, 3 gateway replicas)

| PEAK (req/s) | hold (req/s) | p99 | err % | saga lag | binding resource |
|---|---|---|---|---|---|
| 1800 | ~1,800 | 167 ms | 0 % | 0 (transfers 6) | gateways ~1.4 cores each (balanced ×3); transfers ~2.0 |
| **2200** | **~2,200** | **115 ms** | **0 %** | **0 all groups** | gateways ~1.5 cores each; **transfers ~2.5 (clean knee)** |
| 2800 | ~2,400 eff. | 266 ms (894 tail) | 0 % hard (0.03 % >800ms) | bounded | **transfers ~2.7 cores — p99 knees** |

- **Clean write-accept knee ≥ 2,200 transfers/s** (p99 115 ms, **0 % error**, saga lag **0** on
  accounts/transfers/gateway) — **up from ~1,400/s; the 2,000/s target is reached and exceeded.**
- **The saturating resource moved again** — BANK-18 Postgres CPU → BANK-19b single-gateway CPU →
  **BANK-20 the single-instance transfers service** (~2.5–2.8 cores + 2g RAM). The 3 gateway replicas
  balanced at ~1.5 cores each (verified all three ~145–150 % during the 2,200 hold); Postgres ~1.3
  cores, Redpanda ~0.9, accounts ~3 %, nginx ~30–45 % — none binding.

## Honest ceiling + a robustness finding

**2k is met and exceeded (2,200 clean); 2,800 is the soft edge** (0 % hard error but p99 knees as
transfers nears 3 cores). The next lever past ~2.2k is **horizontal transfers replicas** (same
stateless-edge argument; the source lock + Σ=0 stay per-account in shared Postgres) — **not sharding.**

A **pre-existing** saga-relay bug surfaced only under an abruptly-cut extreme run (transfers pegged at
its 2g `mem_limit`): a failed `transfer-screened` record cannot be routed to its DLT because the DLT
producer uses `StringSerializer` for the Avro value (`ClassCastException`), so it retries forever and
grows `transfers` lag. It is **unrelated to BANK-20** (the gateway-scaling commits touched only
compose + nginx + the Postgres conf, no service code) and **did not occur in any clean ≤2,200 run**.
Tracked as a separate transfers DLT-config fix.

## Money-safety boundary (non-negotiable, honored)

- **No money logic at the edge** — replicas only validate/idempotency-gate/proxy/project.
- **Idempotency (shared Redis) + projection inbox dedup (shared Postgres, `(listener, messageId)`)
  verified correct across replicas** (above).
- `synchronous_commit=on` for accounts/transfers/antifraud/notifications **unchanged**; `off` only for
  the rebuildable gateway projection. `acks=all` + idempotent producer retained.
- **`ConcurrentDebitIT` (overdraft gate), `PostTransferIT`, gateway `TransferProjectionIT` green. No
  sharding.**

## Consequences

- The deployed stack now publishes a single nginx edge in front of N gateway replicas
  (`--scale gateway=N`); the bench points at it unchanged (`BENCH_GATEWAY_URL` default still :8080).
- **Per-instance rate-limit caveat:** bucket4j is in-process per replica, so a caller's effective
  limit is **N × the per-replica limit** under round-robin. The prod fix is a **Redis-shared rate
  limit** (bucket4j supports a Lettuce/Redis backend) so all replicas share one bucket per caller —
  **noted, not built** here (the `X-Forwarded-For` already gives each replica the real client key, so
  a shared bucket would be correct as-is). See `BENCHMARKS.md` §10.5.
- `BENCHMARKS.md` §10 records the rebalance, the LB, the keepalive finding, the new knee, the
  replica-correctness proofs, and the next lever.
- Future work to push past ~2.2k: replicate transfers next — not sharding.
