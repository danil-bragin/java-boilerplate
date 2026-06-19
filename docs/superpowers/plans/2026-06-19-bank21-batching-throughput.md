# BANK-21: batching + transfers replication → toward 5k Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** Cut per-transfer commit/fsync amplification with money-safe batch listeners (including the ledger posting), replicate the new bottleneck (transfers), and re-bench toward 5k transfers/s — WITHOUT weakening any money-safety guarantee. Honest about the 14-core CPU ceiling.

**Architecture:** Batching reduces N commits → 1 per poll. The ledger posting is batchable safely because postings are keyed by source account → same-account postings land in one poll batch → processing them in ONE transaction serializes them (the in-tx SUM sees earlier postings' uncommitted entries → overdraft still impossible, in fact stricter), a business rejection is a normal result (not a rollback), and only an infra error throws `BatchListenerFailedException(i)` (commit-up-to-i + retry, anchor-idempotent). transfers replicas (behind a client/LB) scale the BANK-20 bottleneck. The 14-core box caps the full-saga throughput; batching + replicas push toward it, hop-reduction (Phase 2) is the lever beyond.

**Tech Stack:** Spring Kafka batch listeners (`BatchListenerFailedException`), the BANK-19b group-commit Postgres, Docker compose scaling + LB, the BANK-13/18 Gatling harness.

> Spec: the 5k throughput goal, money-safe, no compromise. Builds on BANK-0..20.
> **Environment (every task):** work from `/Users/npden4ik/Projects/java-boilerplate`, on `master`; system `gradle` (8.14); JDK 21; Docker up (VM 24GB). Heavy live runs — back off if unstable; `down -v` after. `gradle <module>:spotlessApply` before commits.

## Money-safety gate (every task)
`ConcurrentDebitIT` (overdraft) stays green; Σ=0 per posting; effectively-once (inbox per-record inside the batch); `synchronous_commit=on` for money DBs; `acks=all`; no sharding. A NEW batch-overdraft regression test is the gate for Task 2.

---

## Task 1: batch the transfers consume-side (low risk)

**Files:** `transfers/.../adapter/in/messaging/ScreeningResultListener.java`, `PostingResultListener.java`, the listener factory config, `application.yaml`, ITs.

- [ ] **Step 1:** Convert `ScreeningResultListener` + `PostingResultListener` to BATCH listeners (`@KafkaListener(..., batch="true")` or a batch container factory). Each batch handler loops records: per-record `inbox.firstTime(...)` dedup → load transfer → transition (approve/markPosting / complete/fail) → publish next event → (the save+publish accumulate in the one batch tx). On a per-record BUSINESS issue (already-terminal / unknown) — skip that record (it's not an error). On an INFRA error at index i, throw `BatchListenerFailedException(record, i)` so the container commits up to i and retries from i (idempotent via inbox).
- [ ] **Step 2:** Preserve ordering: these topics are keyed by transferId → a partition's batch is in-order per transfer; processing in loop order preserves it. Confirm the rank/state guards still hold.
- [ ] **Step 3:** ITs: extend `TransferAdvanceIT` to send a BATCH of screening results (multiple transfers in one poll) and assert all advance correctly + a redelivered one dedups (no double-advance) + a mid-batch poison doesn't lose the rest. Money-safety saga ITs green.
- [ ] **Step 4: commit**
```bash
gradle :examples:acme-bank:transfers:spotlessApply
git add examples/acme-bank/transfers
git commit -m "perf(transfers): batch consume-side listeners (screening/posting results) — fewer commits/transfer, per-record dedup + BatchListenerFailedException"
```

---

## Task 2: batch the accounts ledger posting (HIGH-CARE, money-safe)

**Files:** `accounts/.../adapter/in/messaging/PostingRequestedListener.java`, `PostTransferHandler` (batch entry), the batch factory, `BatchOverdraftIT.java` (new — the gate).

- [ ] **Step 1: failing gate test FIRST** — `BatchOverdraftIT` (Postgres+Redpanda): produce 8 `posting-requested` for the SAME source account (one partition → one batch), distinct transferIds, source funded for exactly 5. With batch listening on, assert: exactly 5 post, 3 `posting-rejected` INSUFFICIENT_FUNDS, source balance == 0 (NEVER negative), every posted transfer Σ=0, exactly the 5 postings' entries written. This proves batching does not break overdraft prevention. Run → must pass after Step 2 (and the design must make it pass).
- [ ] **Step 2:** Convert `PostingRequestedListener` to a BATCH listener that processes the poll in ONE transaction:
  - loop records in order; for each: `inbox.firstTime("accounts", transferId)` (skip if dup) → `pipeline.send(PostTransferCommand)` (the EXISTING handler: `findByIdForUpdate` source lock + derived-balance check + Σ=0 posting OR a rejected result) → on a `posted` result, the entries are written in THIS batch tx; on a `rejected` result, emit `posting-rejected` (a NORMAL outcome — do NOT throw).
  - Because same-source postings are in the same partition/batch and the same tx, each later posting's derived-balance SUM sees the earlier ones' in-tx entries → correct serialization, overdraft impossible. The `findByIdForUpdate` lock is re-entrant within the one tx (still correct; and it guards the rebalance edge across batches).
  - On a TRUE infra error at index i (DB failure, not a business rejection): throw `BatchListenerFailedException(record, i)` → container commits offsets up to i, retries from i; the posting-PK anchor makes the retry idempotent (already-posted → short-circuit).
  - CRITICAL: a business rejection (INSUFFICIENT_FUNDS / ACCOUNT_NOT_OPERATIONAL / ASSET_MISMATCH) must NOT roll back the batch — it's a normal result that emits `posting-rejected`. Only infra errors fail the batch.
- [ ] **Step 3:** Confirm the StronglyConsistent cqrs tx and the batch tx compose correctly — the whole poll runs in one tx (the batch listener is `@Transactional`, the per-record `pipeline.send` joins it via PROPAGATION_REQUIRED). Verify the posting-PK anchor still flushes per posting so a redelivered duplicate within or across batches is caught.
- [ ] **Step 4: run the gate** — `BatchOverdraftIT` green + the BANK-11 `ConcurrentDebitIT` green (the direct-handler 8-thread test still passes — the lock path is unchanged for non-batched callers) + `PostTransferIT` + `PostingFlowIT` green.
- [ ] **Step 5: commit**
```bash
gradle :examples:acme-bank:accounts:spotlessApply
git add examples/acme-bank/accounts
git commit -m "perf(accounts): batch ledger postings in one tx (same-source serialized in-tx -> overdraft still impossible; rejections are results, only infra errors fail the batch)"
```

---

## Task 3: replicate transfers + LB (scale the BANK-20 bottleneck)

**Files:** `compose.bank.yaml` (transfers scalable + an LB or gateway client-side LB), gateway `RestClient` config.

- [ ] **Step 1:** Make `transfers` scalable (`--scale transfers=N`). The gateway→transfers calls must spread across replicas: either (a) front transfers with nginx (like the gateway LB), or (b) configure the gateway's `RestClient`/`RestTransfersClient` base URL to a transfers LB. Prefer an nginx `transfers` upstream (mirror the BANK-20 gateway LB with keepalive). The transfers CONSUME-side (batched listeners) distributes across replicas automatically via consumer groups.
- [ ] **Step 2:** Rebalance resources (24GB, 14 cores): give transfers replicas enough heap/CPU; keep Σ Hikari pools ≤ Postgres `max_connections`. With transfers replicated, accounts may become the next limiter — note it (replicating accounts is the same stateless argument, do it if the bench shows it binding).
- [ ] **Step 3:** Verify: a happy-path transfer through the gateway LB → transfers LB completes (Σ=0); money-safety ITs green. `docker compose config` validates.
- [ ] **Step 4: commit**
```bash
git add examples/acme-bank/compose.bank.yaml examples/acme-bank/nginx
git commit -m "feat(bank): replicate transfers behind an LB (scale the BANK-20 bottleneck; consume-side distributes via consumer groups)"
```

---

## Task 4: re-bench toward 5k + honest ceiling

- [ ] **Step 1:** Bring up the batched + replicated stack (bench overrides). Re-run the BANK-18/20 WRITE sweep through the gateway LB. Ramp to the knee: max sustainable write QPS @ 0% err + saga lag-bounded + p99 + the saturating resource + total CPU used (vs 14).
- [ ] **Step 2:** Iterate cheap money-safe knobs (more transfers/accounts replicas, more gateway replicas, Redpanda cores, batch sizes, pools) until 5k @ 0% err OR the 14-core CPU wall. Record per-step effect.
- [ ] **Step 3:** Report HONESTLY: the final max write QPS, whether 5k was reached, the hard limiter (likely total CPU at 14 cores), and — if short of 5k — that the remaining lever is HOP REDUCTION (Phase 2: synchronous fast-path / merging hops to cut per-transfer CPU), not more replicas. Tear down.

---

## Task 5: BENCHMARKS.md + ADR

**Files:** `examples/acme-bank/BENCHMARKS.md` (§11), `docs/decisions/0031-batching-throughput.md`.

- [ ] **Step 1:** `BENCHMARKS.md` §11 "Batching + transfers replication (BANK-21)": the per-transfer commit reduction from batching, the new write knee, how close to 5k, the CPU-at-14-cores ceiling, the batch money-safety argument (same-source in-tx serialization), and the Phase-2 hop-reduction lever if 5k wasn't reached.
- [ ] **Step 2:** ADR `0031-batching-throughput.md` — batch listeners (incl. the money-safe ledger batching design: same-source serialized in-tx, rejections-are-results, infra-errors-fail-batch, anchor-idempotent), transfers replication, the measured result, the honest 14-core ceiling, the Phase-2 lever.
- [ ] **Step 3:** `gradle build` green. Commit.
```bash
git add examples/acme-bank/BENCHMARKS.md docs/decisions/0031-batching-throughput.md
git commit -m "docs(benchmarks): batching + transfers-replication results (toward 5k) + ADR 0031"
```

---

## Done criteria for BANK-21

- Consume-side + ledger-posting batched, money-safe (BatchOverdraftIT + ConcurrentDebitIT green; same-source in-tx serialization; rejections-are-results; infra-errors-fail-batch; anchor-idempotent; per-record inbox dedup).
- transfers replicated behind an LB; consume-side distributes via consumer groups.
- Write sweep re-run: the new max sustainable write QPS @ 0% err recorded; how close to 5k + the hard limiter named honestly (CPU at 14 cores). If <5k, the Phase-2 hop-reduction lever is documented.
- Money-safety intact; no sharding; `gradle build` green; BENCHMARKS §11 + ADR 0031.

---

## Self-review notes

- **Money-safety (the crux for Task 2):** batching the ledger is safe BECAUSE same-source postings are co-partitioned → same batch → one tx → in-tx SUM serializes them → overdraft impossible (stricter than per-record). Rejections are normal results (emit posting-rejected), NOT batch rollbacks. Only infra errors throw `BatchListenerFailedException` → commit-up-to-i + idempotent retry. `BatchOverdraftIT` is the non-negotiable gate; `ConcurrentDebitIT` (direct handler) stays green. Σ=0/inbox/anchor unchanged.
- **Honesty:** 14 cores is the hard wall; at ~5 millicore/transfer, 5k ≈ 25 cores > 14. Batching cuts commit/poll overhead (+20-40%), replicas scale to the wall → expect ~3-3.5k full-saga. TRUE 5k needs hop reduction (Phase 2). Report the real number; don't claim 5k if CPU caps first.
- **Type consistency:** `BatchListenerFailedException(record, index)`; batch container factory; `PostTransferCommand` reused per-record inside the batch; `BatchOverdraftIT` mirrors `ConcurrentDebitIT` assertions over Kafka.
- **Risk:** batching the money path is the highest-care change in the whole project — the gate test + adversarial review must specifically attack: a rejection rolling back siblings, a same-source overdraft slipping through the in-tx serialization, a redelivered duplicate within a batch double-posting, and an infra error losing committed siblings. If any is possible, the batch design is wrong — fall back to per-record posting (BANK-19b) and rely on group-commit + replicas only.
