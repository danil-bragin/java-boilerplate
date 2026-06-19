# 0031 — Money-safe batch listeners + transfers replication → toward 5k (BANK-21)

**Date:** 2026-06-19
**Status:** Accepted

## Context

BANK-20 (ADR-0030, `BENCHMARKS.md` §10) reached the **gateway accept knee of ~2,200 transfers/s** by
replicating the stateless gateway behind an nginx LB, and named the next lever: the **single-instance
transfers service**, plus a per-transfer **commit/fsync amplification** — every saga hop committed once per
record (gateway projection, antifraud screening, transfers status advances, the accounts ledger posting). The
target is **5,000 transfers/s, money-safe, no compromise.**

This ADR records BANK-21: cut the per-transfer commit overhead with **batch listeners — including the money
ledger posting** — and **replicate the transfers bottleneck**, then re-bench honestly against the 14-core
ceiling.

## Decision

1. **Batch the consume-side saga listeners** (transfers screening-result + posting-result): a poll of N
   records is applied in **one** transaction (one commit) instead of N.
2. **Batch the accounts ledger posting** (the MONEY path) in **one** transaction per poll — provably
   overdraft-safe and partial-failure-safe (design below). This is the highest-care change in the project; a
   new `BatchOverdraftIT` is the non-negotiable gate.
3. **Replicate transfers behind an nginx LB** (mirror the BANK-20 gateway LB) so the gateway→transfers
   RestClient spreads across replicas; the consume-side distributes via the consumer group automatically.
4. **Re-bench** the write sweep and report the real knee + the honest limiter.

### Why batching the money posting is safe — in fact STRICTER than per-record

`posting-requested` is keyed by the **source accountId** (BANK-15), so all of one source's postings
co-partition → land in **one** poll batch → are processed in **one** transaction (the batch listener is
`@Transactional`; each per-record `pipeline.send(PostTransferCommand)` joins it because `StronglyConsistent`'s
`TransactionMiddleware` uses a `PROPAGATION_REQUIRED` `TransactionTemplate`). Within that one tx:

- Each posting takes the BANK-11 `findByIdForUpdate` **source lock** (re-entrant within the tx) and reads its
  derived balance with a **JPQL** `SELECT SUM(amount)`. Hibernate `FlushMode.AUTO` **flushes the earlier
  in-batch postings' `ledger_entry` inserts before** running that query, so each later posting's SUM **sees
  the earlier (uncommitted) siblings**. The postings therefore **serialize within the tx** and an overdraft is
  **impossible — strictly more so than per-record**, where a posting only saw *committed* entries.
- The per-posting `postings.saveAndFlush(anchor)` (the BANK-1 idempotency PK) still flushes per posting, so a
  redelivered duplicate — within or across batches — short-circuits.

### Rejections are results; only infra errors fail the batch

A business rejection (INSUFFICIENT_FUNDS / ACCOUNT_NOT_OPERATIONAL / ACCOUNT_ASSET_MISMATCH) is a normal
`PostTransferResult.rejected(..)` **return** — the handler never throws — so the listener emits
`posting-rejected` and **continues**; it **never rolls back** the batch's already-posted siblings. Only a
**true infra error** (DB down) propagates; the listener wraps it in `BatchListenerFailedException(record, i)`
so the `DefaultErrorHandler` commits offsets up to `i` and re-drives from `i`. The re-drive is idempotent (the
posting-PK anchor short-circuits an already-posted transfer), and any posting a rolled-back partial commit
leaves undone is **also** re-driven by the transfers `SagaReconciler` against the ledger.

**No money guarantee depends on the batch commit boundary.** Overdraft-impossibility comes from the source
lock + the in-tx SUM; double-post-impossibility from the per-posting anchor; lost-progress recovery from the
inbox + the reconciler. The commit boundary only changes whether a posting happens **0 or 1** times — never 2
(anchor), never negative (lock + SUM).

### Why `BatchListenerFailedException` partial-commit is acceptable here

The error handler commits the pre-`i` offsets via a direct `consumer.commitSync` **outside** the listener's
DB tx, so on rollback the pre-`i` records' DB effects are discarded while their offsets advance. That is safe
**only because every consumer is idempotent and the saga reconciler re-drives any dropped progress**: a
transfer left un-posted (offset advanced, posting rolled back) is found by the reconciler in `POSTING`,
reconciled against the accounts ledger, and re-driven `posting-requested` (idempotent via the accounts inbox +
anchor). The money invariants (no overdraft, Σ=0, no double-post) hold regardless. We deliberately do **not**
introduce a Kafka container transaction on the money path — it would change the posting's commit semantics for
no safety gain over the existing anchor+lock+reconciler net.

### Changes

- `transfers/.../BatchListenerConfig` + `accounts/.../BatchListenerConfig`: a batch
  `ConcurrentKafkaListenerContainerFactory` wired through Boot's
  `ConcurrentKafkaListenerContainerFactoryConfigurer` so the messaging starter's `DefaultErrorHandler` → DLT
  (batch-aware, honors `BatchListenerFailedException`) is attached; concurrency/auto-startup/fetch tuning
  preserved.
- `ScreeningResultListener`, `PostingResultListener`, `PostingRequestedListener`: now
  `List<ConsumerRecord<..>>` batch methods with per-record inbox dedup inside the loop.
- `nginx/transfers.conf` + `compose.bank.yaml`: a `transfers-lb` nginx (static `upstream` + `keepalive`,
  passes `Authorization` + `Idempotency-Key`); `transfers` is `--scale`-able (no host port); gateway
  `TRANSFERS_BASE_URL` → `http://transfers-lb:80`.

## The money-safety gate: `BatchOverdraftIT`

8 `posting-requested` for the **same** source (one partition → one batch → one tx), funded for **exactly 5**:

| Assertion | Result |
|---|---|
| postings that succeed | **exactly 5** (`ledger-posted`) |
| postings rejected | **exactly 3** (INSUFFICIENT_FUNDS) |
| source balance | **exactly 0.00, never negative** |
| each posted Σ | **= 0** |
| ledger rows | **10 entries (5×2), 5 postings** |
| rejected transfers | **0 entries** |

Green alongside `ConcurrentDebitIT` (the BANK-11 direct-handler overdraft gate — lock path unchanged for
non-batched callers), `PostTransferIT`, `PostingFlowIT`, `TransferAdvanceIT` (extended: batch-of-6 +
in-batch redelivery dedup), `ScreeningIT`, `NotificationIT`, gateway `TransferProjectionIT`.

## Live verification (gateway×2 + transfers×3 behind both LBs)

- **transfers LB chain:** gateway `TRANSFERS_BASE_URL=http://transfers-lb:80` → nginx (`transfers_pool`
  upstream) → round-robins the 3 `transfers:8080` replicas. Confirmed reachable + config loaded.
- **Consume-side distribution:** the `transfers` consumer group had members on **3 distinct replica IPs**,
  lag 0 — the saga lanes spread across replicas via the group, no extra wiring.
- **Happy path through both LBs:** 75 POSTs → 100 % 202, 0 KO; saga settled; **global ledger Σ = 0**, 0
  unbalanced transfers.

## Results (full-saga open-model write sweep, 24 GiB / 14-core VM, gateway=2 / transfers=3)

| Offered (req/s) | KO % | p99 | mean rps | total CPU (of 14 cores) | saga lag | binding |
|---|---|---|---|---|---|---|
| **1200** | **0 %** | **427 ms** | **1,178** | ~71 % (~993/1400) | bounded (≤2, accounts 0) | comfortable knee |
| **1500** | **0 %** | ~1.9 s | **1,447** | ~74 % (~1040/1400) | bounded (≤6, accounts 0) | **clean knee — p99 stressed** |
| 1700 (gw2) | 39.6 % | 44 s | — | ~71 % (not pegged) | 0 | **gateway accept tier queues** |
| 1700 (gw3) | host destabilized | — | — | overcommit | 0 | **co-located driver + fleet > 14 cores** |

- **Clean full-saga write knee ≈ 1,500 transfers/s at 0 % error**; **~1,200/s comfortable** (p99 427 ms). The
  cliff is **~1,700/s** (latency collapse, then KO). **5k was NOT reached.**
- **The batching win is real but invisible at the accept tier:** accounts CPU fell to **~4–10 %** and the
  saga lag stayed **bounded/0 even at the knee** — the ledger posting is now nearly free, and the consume-side
  is no longer a limiter. The limiter at the cliff is the **gateway accept/JWT/proxy hop** (~175 %/replica),
  not the money path.
- **The hard limiter is total CPU on the 14-core box, shared with the co-located Gatling driver.** Scaling
  gateway 2→3 did **not** lift the knee — it pushed the host into instability (Gatling connections timed out,
  the Gradle daemon was killed). On a dedicated cluster the accept tier scales further; on one box, 14 cores
  is the wall.

## Honest 5k arithmetic + the Phase-2 lever

At **~5 millicore/transfer** end-to-end, **5,000/s ≈ 25 cores** — nearly **2×** the 14-core box, before the
load generator. Batching cut the per-transfer commit/poll overhead and replicas scale the accept tier to the
wall, but **no amount of replication beats the total-CPU ceiling**. **5k is unreachable on 14 cores.** The
remaining lever is **hop reduction (Phase 2)** — cut the **CPU per transfer**, not add boxes: a synchronous
fast-path or merging saga hops (fold screening into the request; post inline for the common case) so each
transfer costs fewer cores. The honest path to 5k is **fewer cores/transfer, then a dedicated/larger cluster.**

## Money-safety boundary (non-negotiable, honored)

- **Overdraft impossible, verified two ways:** `BatchOverdraftIT` (8-deep same-source batch → 5 post / 3
  reject / balance 0, never negative) **and** the post-bench ledger after hundreds of thousands of loaded
  transfers: **global Σ = 0, 0 unbalanced transfers, 0 negative real-account balances, 0 duplicate postings.**
  `ConcurrentDebitIT` green.
- **The batched money path is STRICTER than per-record** (in-tx SUM sees uncommitted in-batch siblings).
  `synchronous_commit=on` for the money DBs, `acks=all` + idempotent producer, per-record inbox dedup, the
  per-posting `saveAndFlush` anchor, and the BANK-11 source lock are **unchanged**. **No sharding.**
- A rejection emits `posting-rejected` as a normal result and **never** rolls back posted siblings; only a
  true infra error fails the batch (commit-up-to-i + idempotent re-drive via anchor + reconciler).

## Consequences

- The deployed stack adds a `transfers-lb` internal nginx; `transfers` runs `--scale transfers=N`. The
  consume-side distributes via the `transfers` consumer group with no code change.
- The money path is now batched but **provably no weaker** — the gate test + the post-bench ledger prove it.
  If a future change cannot keep the batch overdraft-safe AND partial-failure-safe, the fallback is per-record
  posting (BANK-19b) + group-commit + replicas only.
- `BENCHMARKS.md` §11 records the batching design, the gate, the new knee, the 14-core limiter, and the
  Phase-2 lever.
- **Phase 2 (next):** hop reduction to cut per-transfer CPU — the honest path past ~1.5k toward 5k.
