# BANK-19b: write-throughput saturation — load the hardware Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** Drive write throughput toward 2k transfers/s on the CURRENT host by actually saturating the fast CPU/RAM/disk — the stack today uses a fraction of the box because Postgres runs default config, Redpanda is pinned to 1 core, JVM heaps/pools are untuned, and each transfer commits ~6 times across the saga. Close the gap WITHOUT sharding and WITHOUT weakening strong consistency (no overdraft, Σ=0, idempotent, `acks=all`, `synchronous_commit=on` for money DBs).

**Architecture:** Diagnose first (where the per-transfer Postgres CPU actually goes), then tune the infra + the per-transfer commit amplification with money-safe levers: a hardware-tuned Postgres config (incl. group-commit to batch fsyncs WITHOUT losing durability), Redpanda given real cores, right-sized Hikari pools, per-database `synchronous_commit` (off only for the rebuildable gateway projection, on for money), and batch listeners ONLY on the non-money idempotent hops (screening, projection). Re-benchmark toward 2k.

**Tech Stack:** Postgres 16 tuning, Redpanda cores, HikariCP, Spring Kafka batch listeners, the BANK-13/18 Gatling harness, `pg_stat_statements`.

> Follows BANK-18 (capacity). The user's directive: hardware is capable of 2k write/s; we're not saturating it — find what's missing, money-safe, no sharding. Builds on BANK-0..18 + BANK-19a (gateway OOM fix).
> **Environment (every task):** work from `/Users/npden4ik/Projects/java-boilerplate`, on `master`; system `gradle` (8.14); JDK 21; Docker up; all images cached. Heavy live runs — back off if the host destabilizes. Tear down (`down -v`) after each run. `gradle <module>:spotlessApply` before commits. RAM is the tight resource (co-located) — every tuning value must fit the stack in the Docker VM; CHECK the VM's CPU/RAM allocation first (`docker info` — if Docker Desktop is capped below the host, raising it is a prerequisite the user controls; report it).

---

## Task 1: DIAGNOSE — where does the per-transfer Postgres CPU go?

**Files:** none yet (investigation); capture findings for the report.

- [ ] **Step 1:** Check the Docker VM resource cap: `docker info | grep -iE "CPUs|Total Memory"`. If the VM has far fewer CPUs / less RAM than the physical host (Docker Desktop default), that is a first-order limiter the user must raise — RECORD it and note it gates everything.
- [ ] **Step 2:** Bring up the stack (`gradle bankJars && docker compose -f examples/acme-bank/compose.bank.yaml up -d --build --wait`). Enable `pg_stat_statements` (add `shared_preload_libraries=pg_stat_statements` to the Postgres tuning in Task 2 if not present — for the diagnosis run you may enable it via a temporary `command:`), reset stats, drive a steady write load (e.g. the BANK-18 open-model write sim at ~400/s for 60s), then dump the top statements by total exec time per database: `SELECT query, calls, total_exec_time, rows FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 25;` for accounts + transfers + the outbox tables.
- [ ] **Step 3:** Quantify the per-transfer DB amplification: count, per single transfer, the commits and row-ops across the saga — the Modulith `event_publication` inserts + completion updates (≈6 events → ≈6 insert + ≈6 complete), the `processed_messages` inbox inserts (≈5), the `ledger_entry`/`posting` inserts, the `transfer` insert+updates, and the derived-balance `SELECT SUM(...)` per posting. Identify the TOP 3 costs (likely: outbox churn, fsync-per-commit × ~6 commits, the balance SUM, or WAL on default config).
- [ ] **Step 4:** Capture the baseline saturating resource at ~400/s: Postgres CPU, WAL/checkpoint activity (`pg_stat_bgwriter`, checkpoint frequency), Redpanda CPU (the 1-core cap), per-service CPU, Hikari active/pending. Tear down.
- [ ] **Step 5:** Write the diagnosis into the report draft (no commit yet) — the top per-transfer costs + the saturating resource + the Docker VM cap. This DRIVES the tuning in Tasks 2-4.

---

## Task 2: tune Postgres for the hardware (money-safe durability kept)

**Files:** `examples/acme-bank/compose.bank.yaml` (Postgres `command:` / a mounted `postgresql.conf`), `examples/acme-bank/db/postgresql.tuning.conf` (new).

- [ ] **Step 1:** Add a tuned Postgres config (mount a conf file or pass `-c` flags via `command:`), RAM-aware for the co-located VM (scale to whatever the VM actually has — do NOT exceed it):
  - `shared_buffers` ≈ 25% of the VM RAM available to Postgres (e.g. 1–2GB), `effective_cache_size` ≈ 50%, `work_mem` modest (e.g. 16–32MB — bounded × connections), `maintenance_work_mem` higher.
  - WAL/checkpoint: `max_wal_size` 4GB, `min_wal_size` 1GB, `checkpoint_completion_target` 0.9, `wal_buffers` 64MB, `wal_compression` on. These cut checkpoint storms (a default `max_wal_size=1GB` forces frequent checkpoints under write load — a prime default-config killer).
  - **Group commit (the key money-safe fsync lever):** `commit_delay` (e.g. 50–100µs) + `commit_siblings` (e.g. 5) — batches WAL fsyncs across concurrent commits WITHOUT weakening durability (each commit is still durably flushed, just grouped). This directly attacks the ~6-commits-per-transfer fsync cost. **Keep `synchronous_commit=on`** (money durability) — do NOT set it off globally.
  - `max_connections` matched to the total Hikari pools (Task 3) + headroom.
  - `shared_preload_libraries=pg_stat_statements` (keep for observability).
- [ ] **Step 2:** Verify Postgres starts with the tuned config (`SHOW shared_buffers;` etc.) and the money-safety durability is intact (`SHOW synchronous_commit;` → `on`). Run the accounts + transfers ITs against a tuned Testcontainers Postgres if feasible (or just the deployed smoke).
- [ ] **Step 3: commit**
```bash
git add examples/acme-bank/compose.bank.yaml examples/acme-bank/db/postgresql.tuning.conf
git commit -m "perf(bank): tune Postgres for the host (shared_buffers/WAL/checkpoint + group-commit fsync batching); synchronous_commit=on retained"
```

---

## Task 3: Redpanda cores + Hikari pools

**Files:** `compose.bank.yaml` (Redpanda `--smp`, memory; service `mem_limit` already from BANK-19a), each service `application.yaml` (Hikari).

- [ ] **Step 1:** Give Redpanda real cores: raise `--smp` from 1 to 2–4 (fit the VM) and `--memory` accordingly. The saga relay (≈6 msg/transfer × the write rate) is currently single-core-bound; more cores lift the saga drain ceiling.
- [ ] **Step 2:** Right-size HikariCP per service: set `spring.datasource.hikari.maximum-pool-size` (e.g. 16–24 for the hot write-path services accounts/transfers; smaller for read-mostly) so the services can actually drive the tuned Postgres concurrently — but keep the SUM of pools ≤ Postgres `max_connections`. Bound `connection-timeout`/`max-lifetime` sanely.
- [ ] **Step 3:** Verify the saga ITs + money-safety tests still green (`ConcurrentDebitIT`).
- [ ] **Step 4: commit**
```bash
gradle :examples:acme-bank:accounts:spotlessApply :examples:acme-bank:transfers:spotlessApply 2>/dev/null || true
git add examples/acme-bank
git commit -m "perf(bank): give Redpanda multiple cores + right-size Hikari pools to drive the tuned Postgres"
```

---

## Task 4: cut per-transfer commit amplification — batch the NON-money hops + per-db synchronous_commit

**Files:** antifraud + gateway projection listeners (batch), gateway projection DB `application.yaml` (`synchronous_commit=off` via a per-connection setting or the projection datasource), Modulith outbox tuning.

- [ ] **Step 1: per-database `synchronous_commit`.** The gateway `transfer_view` projection is a REBUILDABLE read model (consumes from `earliest`) — it does NOT need strict durability. Set `synchronous_commit=off` for the GATEWAY datasource only (e.g. `spring.datasource.hikari.connection-init-sql: "SET synchronous_commit TO off"` on the gateway, or a per-db Postgres `ALTER DATABASE gateway SET synchronous_commit=off`). This removes the projection's fsync cost from the hot path. **Do NOT touch accounts/transfers/antifraud/notifications** — money + record-keeping stay `on`. Document why this is safe (projection rebuilds from Kafka on loss).
- [ ] **Step 2: batch listeners on the idempotent NON-money hops.** Where partial-failure-money-semantics do NOT apply — the antifraud screening consumer and the gateway projection consumers — switch to Spring Kafka BATCH listeners (`spring.kafka.listener.type: batch` on that factory / `@KafkaListener(batch=true)`) so a poll of N records is processed in fewer transactions (amortized commit/fsync), preserving per-record inbox dedup inside the batch. Do NOT batch the accounts POSTING consumer in a way that lets one rejected posting roll back others — the money path keeps per-posting transactions; its fsync cost is addressed by group-commit (Task 2), the durable, money-safe way. Verify ordering + dedup hold under batching.
- [ ] **Step 3:** (If the diagnosis flagged it) reduce Modulith outbox churn — e.g. tune the externalization/completion so the `event_publication` table isn't a hotspot (batch completion, or the appropriate Modulith completion mode). Only if Task 1 showed the outbox as a top-3 cost.
- [ ] **Step 4:** Run the money-safety + saga ITs → green (`ConcurrentDebitIT`, `PostTransferIT`, `ScreeningIT`, `TransferAdvanceIT`, `NotificationIT`, gateway projection IT). Batching/sync_commit changes must not break dedup, ordering, or Σ=0.
- [ ] **Step 5: commit**
```bash
gradle :examples:acme-bank:spotlessApply 2>/dev/null || true
git add examples/acme-bank
git commit -m "perf(bank): batch non-money hops + synchronous_commit=off for the rebuildable gateway projection (money DBs stay on)"
```

---

## Task 5: re-benchmark toward 2k write/s + BENCHMARKS.md + ADR

**Files:** `examples/acme-bank/BENCHMARKS.md` (§9), `docs/decisions/0029-write-throughput-tuning.md`.

- [ ] **Step 1:** Bring up the tuned stack (with the BANK-19a mem-limits + BANK-19b Postgres/Redpanda/pool/batch tuning). Re-run the BANK-18 write-capacity sweep (open-model, bench rate-limit/breaker overrides) to the new knee. Record the new max write-accept QPS + sustainable saga QPS (lag-bounded) + the NEW saturating resource. Compare to the BANK-18 baseline (~550/s).
- [ ] **Step 2:** Iterate if a clear next bottleneck appears and is a cheap money-safe knob (e.g. checkpoint still storming → bump `max_wal_size`; Redpanda still capped → more cores; pool starvation → bigger pool). Record each step's effect. Stop when at 2k OR when the binding resource is genuinely maxed (CPU/disk/RAM at ceiling) — report honestly how close to 2k and the hard limiter.
- [ ] **Step 3:** Update `BENCHMARKS.md` §9 "Write-throughput saturation (BANK-19)": the diagnosis (per-transfer amplification, default-config gap, Docker VM cap), each tuning lever's measured effect, the new write knee, how close to 2k, and the remaining hard limiter. Confirm money-safety intact (synchronous_commit=on for money, `ConcurrentDebitIT` green). ADR `0029-write-throughput-tuning.md` — the money-safe levers (group-commit not sync_commit-off for money; batch only non-money hops; per-db sync_commit; Postgres-for-hardware), what got us toward 2k, the honest remaining ceiling.
- [ ] **Step 4: commit**
```bash
git add examples/acme-bank/BENCHMARKS.md docs/decisions/0029-write-throughput-tuning.md
git commit -m "docs(benchmarks): write-throughput saturation results (toward 2k) + ADR 0029 money-safe tuning"
```

---

## Done criteria for BANK-19b

- Diagnosis identifies the real per-transfer costs + the Docker VM cap + the default-config gaps.
- Postgres tuned for the hardware (shared_buffers/WAL/checkpoint + group-commit) with `synchronous_commit=on` retained for money DBs; Redpanda given real cores; Hikari pools right-sized; non-money hops batched; gateway projection `synchronous_commit=off` (rebuildable).
- Write throughput measurably up from ~550/s toward 2k, with the new saturating resource identified; if 2k isn't reached on this host, the hard limiter is named honestly.
- Money-safety intact (no overdraft / Σ=0 / idempotency; `ConcurrentDebitIT` green; money DBs durable); no sharding.
- `gradle build` green; BENCHMARKS.md §9 + ADR 0029.

---

## Self-review notes

- **The crux:** we under-use the box because Postgres is default-config, Redpanda is 1-core, heaps/pools are untuned, and each transfer commits ~6× (saga amplification). Tune the infra + batch the fsyncs (group-commit, money-safe) + batch the non-money hops — without sharding or weakening money durability.
- **Money-safety boundary (critical):** `synchronous_commit` stays `on` for accounts/transfers/antifraud/notifications (money + records); off ONLY for the rebuildable gateway projection. Group-commit (`commit_delay`) batches fsyncs WITHOUT weakening durability — that's the money-safe way to cut commit cost, NOT `synchronous_commit=off`. Batch listeners ONLY where partial-failure has no money meaning (screening, projection); the accounts posting stays per-posting-tx + the source lock + Σ=0. `ConcurrentDebitIT` is the gate.
- **Honesty:** RAM is tight co-located; if the Docker VM is capped, that gates everything (user-controlled). Report how close to 2k and the true hard limiter rather than claiming 2k if the box can't.
- **No placeholders:** concrete Postgres params, Redpanda cores, pool sizes, batch/sync_commit targets, pg_stat_statements diagnosis.
- **Type consistency:** per-service Hikari `maximum-pool-size` ≤ Postgres `max_connections`; bench overrides reused from BANK-18; `ConcurrentDebitIT` regression gate.
