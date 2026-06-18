# BANK-20: gateway horizontal scaling → 2k write/s Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** Push write throughput from ~1,400/s toward 2,000/s on the now-24GB Docker VM by horizontally scaling the gateway (the BANK-19b bottleneck = gateway CPU) behind a load balancer, rebalancing resources for the larger VM, and re-benchmarking to prove ≥2k transfers/s at 0% error — money-safe, no sharding, strong consistency intact.

**Architecture:** The gateway is stateless (shared Redis idempotency, shared-Postgres `transfer_view` projection, per-partition single-writer downstream), so N replicas behind an nginx round-robin LB multiply the edge CPU ceiling. The Kafka projection consumers distribute across replicas via their consumer groups; projection rows land in the shared gateway DB so reads from any replica are consistent. With 24GB the per-service mem_limits + Postgres shared_buffers + Redpanda cores are scaled up. Re-bench the BANK-18 write sweep through the LB.

**Tech Stack:** Docker Compose (scaled service + nginx LB), the BANK-13/18 Gatling harness, the BANK-19b tuned Postgres/Redpanda.

> Follows BANK-19 (gateway OOM fix + write tuning to ~1.4k, bottleneck = gateway CPU + RAM, gated by the 8GB VM — now raised to 24GB by the user). Builds on BANK-0..19.
> **Environment (every task):** work from `/Users/npden4ik/Projects/java-boilerplate`, on `master`; system `gradle` (8.14); JDK 21; Docker up (VM now 24GB — verify `docker info`). Heavy live runs — back off if unstable; `down -v` after. `gradle <module>:spotlessApply` before commits.

## Money-safety boundary (unchanged, non-negotiable)
The gateway holds NO money logic — replicas don't touch the money path. The two things to VERIFY stay safe under replication: (a) idempotency is shared (Redis) so a repeated `Idempotency-Key` to a DIFFERENT replica still replays / 409s — not double-submits; (b) the projection inbox dedup is in shared Postgres keyed by `(listener, messageId)` so even if two replicas' consumers transiently see the same record during a rebalance, it's deduped. `synchronous_commit=on` for money DBs, `ConcurrentDebitIT` green, no sharding — all unchanged.

---

## Task 1: nginx LB + scalable gateway in compose

**Files:** `examples/acme-bank/compose.bank.yaml`, `examples/acme-bank/nginx/gateway.conf` (new).

- [ ] **Step 1:** Verify the VM: `docker info | grep -iE "CPUs|Total Memory"` → expect ~24GB now. Record it.
- [ ] **Step 2:** Change the `gateway` service: remove its host port mapping (`ports: ["8080:8080"]`), keep it on the internal network, make it scalable (`deploy.replicas: ${GATEWAY_REPLICAS:3}` — note compose v2 `--scale gateway=N` also works; prefer `--scale` so the existing healthcheck/depends_on stay simple). Raise gateway `mem_limit` (e.g. 1.5g) now RAM is ample.
- [ ] **Step 3:** Add an `nginx` service publishing host `8080:80`, config `nginx/gateway.conf` round-robining to `gateway:8080` (Docker DNS resolves the service name to all replica IPs; nginx `resolver 127.0.0.11` + `upstream` or a simple `proxy_pass http://gateway:8080` with Docker's round-robin). Use `least_conn` or round-robin; pass through `Authorization`, `Idempotency-Key`, and set `X-Forwarded-For`. Healthcheck nginx on `/`.  `depends_on` gateway healthy.
- [ ] **Step 4:** `docker compose -f examples/acme-bank/compose.bank.yaml config` validates. (A live multi-replica smoke is Task 3.)
- [ ] **Step 5: commit**
```bash
git add examples/acme-bank/compose.bank.yaml examples/acme-bank/nginx
git commit -m "feat(bank): nginx LB + horizontally scalable gateway (stateless edge; shared Redis idempotency + Postgres projection)"
```

---

## Task 2: rebalance resources for the 24GB VM

**Files:** `compose.bank.yaml` (mem_limits, Redpanda `--smp`/memory), `db/postgresql.tuning.conf` (shared_buffers etc.), service heaps.

- [ ] **Step 1:** With ~24GB now available, scale up (leave host headroom for Gatling + macOS):
  - Postgres: `mem_limit` up (e.g. 4–6g), `shared_buffers` 256MB → 2–4GB, `effective_cache_size` accordingly, keep the BANK-19b WAL/checkpoint/group-commit tuning + `synchronous_commit=on`.
  - Redpanda: `--smp` 2 → 4, `--memory` up (e.g. 4G).
  - transfers/accounts: `mem_limit` up (e.g. 1.5–2g — transfers was the RAM co-limiter); gateway replicas 1.5g each × 3.
  - Keep the total within ~20GB (headroom for the Gatling JVM + OS).
- [ ] **Step 2:** Hikari pools: with more Postgres RAM + connections, ensure `max_connections` (200) still ≥ Σ pools across all replicas (3 gateways now) — bump `max_connections` if needed.
- [ ] **Step 3:** `docker compose config` validates; money-safety ITs unaffected (config only). `gradle build` green.
- [ ] **Step 4: commit**
```bash
git add examples/acme-bank/compose.bank.yaml examples/acme-bank/db/postgresql.tuning.conf
git commit -m "perf(bank): scale resources for the 24GB VM (Postgres shared_buffers, Redpanda 4 cores, service heaps, pools)"
```

---

## Task 3: verify replicas are correct (idempotency + projection across replicas)

**Files:** a short verification (manual/scripted), not necessarily a committed test.

- [ ] **Step 1:** `gradle bankJars && docker compose -f examples/acme-bank/compose.bank.yaml -f examples/acme-bank/compose.bench.yaml up -d --build --scale gateway=3 --wait`. Confirm 3 gateway replicas healthy behind nginx, VM at 24GB.
- [ ] **Step 2: idempotency-across-replicas check:** POST /v1/transfers with the SAME `Idempotency-Key` twice (the LB will likely hit different replicas) → confirm ONE transfer created, the second replays the same 202 (or 409) — proving Redis-shared idempotency works across replicas. (curl loop through nginx:8080.)
- [ ] **Step 3: projection-across-replicas check:** POST a transfer, await COMPLETED via GET /v1/transfers/{id} hitting the LB repeatedly (different replicas) → all replicas return the same COMPLETED view (shared Postgres projection; consumers distributed across replicas). Confirm no double-processing (the transfer's notification/ledger is single).
- [ ] **Step 4:** A quick happy-path e2e through the LB still passes (open accounts → transfer → COMPLETED → balances). Reuse the BANK-10 e2e scenario manually if the e2e module can't easily point at the LB. Record the result.
- [ ] **Step 5:** (no commit unless a config fix was needed)

---

## Task 4: re-bench write sweep → prove toward 2k

- [ ] **Step 1:** With the scaled stack up (3 gateways, 24GB, bench overrides), re-run the BANK-18 open-model WRITE sweep through nginx:8080. Ramp until the knee: record max write-accept QPS + sustainable saga (lag-bounded) QPS + p99 + err% + the NEW saturating resource (expect it to move off the single-gateway CPU — onto transfers, Postgres, or Redpanda).
- [ ] **Step 2:** If <2k and a clear cheap money-safe knob remains (more gateway replicas, more transfers heap/CPU, more Redpanda cores, bigger pool), apply and re-measure. Iterate until ≥2k @ 0% err OR a genuine hard resource ceiling (CPU/disk/RAM maxed). 
- [ ] **Step 3:** Record the final number honestly: the max sustainable write QPS at 0% error, the resource that caps it, and whether 2k was reached. Tear down (`down -v`).

---

## Task 5: BENCHMARKS.md + ADR

**Files:** `examples/acme-bank/BENCHMARKS.md` (§10), `docs/decisions/0030-gateway-scaling.md`.

- [ ] **Step 1:** `BENCHMARKS.md` §10 "Gateway scaling → 2k (BANK-20)": the 24GB VM, the LB + N replicas, the before (1.4k single gateway) / after (N replicas) write knee, the saturating resource at each step, whether 2k @ 0% err was reached, and the per-instance rate-limit caveat (in-process bucket4j → effective limit scales with replicas; a Redis-shared rate-limit would be the prod fix, noted not built).
- [ ] **Step 2:** ADR `0030-gateway-scaling.md` — stateless gateway replicas behind nginx as the horizontal lever; why it's money-safe (shared Redis idempotency + shared-Postgres projection + inbox dedup; no money logic at the edge); the measured result; the remaining lever if still <2k (more replicas / split Postgres / Redpanda). 
- [ ] **Step 3:** `gradle build` green (config + docs only). 
- [ ] **Step 4: commit**
```bash
git add examples/acme-bank/BENCHMARKS.md docs/decisions/0030-gateway-scaling.md
git commit -m "docs(benchmarks): gateway scaling results (toward/at 2k write) + ADR 0030"
```

---

## Done criteria for BANK-20

- Gateway runs as N stateless replicas behind an nginx LB; idempotency (Redis) and the projection (shared Postgres, distributed consumers) proven correct across replicas.
- Resources rebalanced for the 24GB VM (Postgres/Redpanda/heaps/pools scaled).
- Write sweep re-run: the new max sustainable write QPS @ 0% err recorded — ideally ≥2k; if not, the honest hard limiter named.
- Money-safety intact (no money logic at the edge; `synchronous_commit=on` for money; `ConcurrentDebitIT` green; no sharding).
- BENCHMARKS.md §10 + ADR 0030; `gradle build` green.

---

## Self-review notes

- **Spec/intent:** lever #2 from the 2k analysis (gateway replicas) now that the user raised the VM (lever #1). gateway CPU was the BANK-19b limiter → horizontal gateway scaling directly attacks it.
- **Money-safety:** replicas touch NO money logic. Verify (Task 3) the two shared-state correctness points: Redis idempotency across replicas + projection inbox dedup in shared Postgres. Money DBs stay `synchronous_commit=on`; posting path unchanged; `ConcurrentDebitIT` gate.
- **Honesty:** report the real measured knee; if ≥2k, prove it at 0% err with the saga lag-bounded; if not, name the next limiter. Do not claim 2k without the measurement.
- **Caveat to document:** in-process bucket4j rate-limit is per-replica → effective per-caller limit scales with replica count (a Redis-shared rate-limit is the prod fix; noted, not built — separate from this capacity work).
- **Risk:** nginx → gateway round-robin must pass `Authorization` + `Idempotency-Key` intact; `X-Forwarded-For` so the gateway sees the real client (rate-limit key) — though the bench overrides the limit anyway. Projection consumers across 3 replicas × concurrency 6 vs 6 partitions = only 6 active per group, rest idle (harmless). Shared Postgres connection count rises with replicas → keep Σ pools ≤ `max_connections`.
