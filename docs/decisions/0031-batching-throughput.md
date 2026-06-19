# 0031 — Transfers replication retained; money/saga application-batch REVERTED (BANK-21)

**Date:** 2026-06-19 (amended 2026-06-19 after a money-safety review)
**Status:** Amended — the **application-level batch listeners were REVERTED to per-record**; the transfers
replication is **retained**.

## Revert (the decision that supersedes the original "batch the money/saga path")

A money-safety review found a **CRITICAL latent bug** in the batched money/saga consume-side. The batch
listeners were `@Transactional` methods receiving `List<ConsumerRecord<..>>` on a **non-transactional** Kafka
container, surfacing mid-batch failures with `BatchListenerFailedException(record, i)`. On a true infra error
at record `i`, the container's `DefaultErrorHandler` **commits the offsets of records 0..i-1 and re-drives
from i** — but the single batch DB transaction has **rolled back**, so those records' DB writes are gone
while their offsets are committed. They are **never re-delivered → money movements silently lost** (the only
recovery was the `SagaReconciler`, a fragile and untested path).

The batch also did **not** improve demonstrable throughput — it measured **~1,500/s full-saga vs BANK-20's
~2,200/s gateway accept knee** — so reverting loses **no proven throughput**. Therefore:

1. **accounts `PostingRequestedListener` (the MONEY path), transfers `ScreeningResultListener` +
   `PostingResultListener`, antifraud `TransferRequestedListener`, and the gateway `TransferStatusProjection`
   listeners are REVERTED to per-record** `@KafkaListener` (single-record method, own tx, own offset). All
   four `BatchListenerConfig` classes and the `BatchListenerFailedException` usage are removed.
2. **Per-record is inherently money-safe:** one record = one tx = one committed offset, so a failure rolls
   back **and** re-drives exactly that record (idempotent via inbox + posting-PK anchor), and a poison is
   isolated cleanly to the DLT. There is no batch boundary across which money can be lost.
3. **The money-safe fsync efficiency comes from BANK-19b Postgres group-commit** (`commit_delay` /
   `commit_siblings`), which batches WAL fsyncs **across concurrent per-record commits at the database
   level** — keeping most of the fsync win **without** the application-batch complexity or its loss bug.
4. **The transfers LB / replication (Task 3) is RETAINED** — it is independent of batching and money-safe.

The honest demonstrable ceiling on this **co-located 14-core host** (load generator on the same cores)
remains BANK-20's **~2.2k/s gateway accept knee**; the architecture is component-cheap and would scale
further on separate infra. The original batching rationale is preserved **below, struck through in intent**,
for the record of why it was tried and why it was unsafe.

---

## Context (original)

BANK-20 (ADR-0030, `BENCHMARKS.md` §10) reached the **gateway accept knee of ~2,200 transfers/s** by
replicating the stateless gateway behind an nginx LB, and named the next lever: the **single-instance
transfers service**, plus a per-transfer **commit/fsync amplification** — every saga hop committed once per
record (gateway projection, antifraud screening, transfers status advances, the accounts ledger posting). The
target is **5,000 transfers/s, money-safe, no compromise.**

This ADR records BANK-21: cut the per-transfer commit overhead with **batch listeners — including the money
ledger posting** — and **replicate the transfers bottleneck**, then re-bench honestly against the 14-core
ceiling.

## Decision (original — items 1 & 2 SUPERSEDED by the Revert above; item 3 retained)

1. ~~**Batch the consume-side saga listeners**~~ — **REVERTED to per-record.**
2. ~~**Batch the accounts ledger posting** (the MONEY path)~~ — **REVERTED to per-record** (the
   `BatchListenerFailedException` + non-transactional-container offset/rollback mismatch could lose money; see
   the Revert section). The original "STRICTER than per-record" argument below assumed the whole batch ran in
   one tx and committed atomically — but offsets are committed by the container **outside** that tx, which is
   exactly the hole.
3. **Replicate transfers behind an nginx LB** (mirror the BANK-20 gateway LB) so the gateway→transfers
   RestClient spreads across replicas; the consume-side distributes via the consumer group automatically.
   **RETAINED.**
4. **Re-bench** the write sweep and report the real knee + the honest limiter.

### (Original rationale, superseded) Why batching the money posting was thought safe

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

### (THE BUG) Why `BatchListenerFailedException` partial-commit was NOT acceptable

> This section was the flawed rationale. It is **the bug**: "the error handler commits the pre-`i` offsets via
> a direct `consumer.commitSync` **outside** the listener's DB tx, so on rollback the pre-`i` records' DB
> effects are discarded while their offsets advance." That was claimed safe because "the saga reconciler
> re-drives any dropped progress" — but the reconciler was **fragile and untested**, so on the money path a
> rolled-back-but-offset-committed posting could be **silently lost**. Relying on a reconciler to undo a
> known offset/rollback mismatch is not a money-safety guarantee. The fix is to remove the mismatch entirely:
> **per-record** processing commits the offset and the DB tx **together**, so a rollback always re-delivers.

The error handler commits the pre-`i` offsets via a direct `consumer.commitSync` **outside** the listener's
DB tx, so on rollback the pre-`i` records' DB effects are discarded while their offsets advance. That is safe
**only because every consumer is idempotent and the saga reconciler re-drives any dropped progress**: a
transfer left un-posted (offset advanced, posting rolled back) is found by the reconciler in `POSTING`,
reconciled against the accounts ledger, and re-driven `posting-requested` (idempotent via the accounts inbox +
anchor). The money invariants (no overdraft, Σ=0, no double-post) hold regardless. We deliberately do **not**
introduce a Kafka container transaction on the money path — it would change the posting's commit semantics for
no safety gain over the existing anchor+lock+reconciler net.

### Changes (final, after the revert)

- **Removed** `accounts/.../BatchListenerConfig`, `transfers/.../BatchListenerConfig`,
  `antifraud/.../BatchListenerConfig`, `gateway/.../BatchListenerConfig` (all four now unused).
- `PostingRequestedListener`, `ScreeningResultListener`, `PostingResultListener`, `TransferRequestedListener`,
  `TransferStatusProjection`: **reverted to single-record `@KafkaListener` + `@Transactional`** (own tx, own
  offset); per-record inbox dedup; no `BatchListenerFailedException`. The notifications listener was already
  per-record.
- `nginx/transfers.conf` + `compose.bank.yaml` (**RETAINED, unchanged**): a `transfers-lb` nginx (static
  `upstream` + `keepalive`, passes `Authorization` + `Idempotency-Key`); `transfers` is `--scale`-able (no
  host port); gateway `TRANSFERS_BASE_URL` → `http://transfers-lb:80`.

## The money-safety gate: `SameSourcePostingOverdraftIT`

The batch gate `BatchOverdraftIT` was **repurposed and renamed** to a per-record concurrent same-source
overdraft test (assertions kept, batch framing dropped). 8 `posting-requested` for the **same** source (one
partition → consumed in offset order, **each in its own per-record tx**), funded for **exactly 5**:

| Assertion | Result |
|---|---|
| postings that succeed | **exactly 5** (`ledger-posted`) |
| postings rejected | **exactly 3** (INSUFFICIENT_FUNDS) |
| source balance | **exactly 0.00, never negative** |
| each posted Σ | **= 0** |
| ledger rows | **10 entries (5×2), 5 postings** |
| rejected transfers | **0 entries** |

Green alongside `ConcurrentDebitIT` (the BANK-11 direct-handler overdraft gate), `PostTransferIT`,
`PostingFlowIT`, `TransferAdvanceIT` (its burst-of-6 + redelivery-dedup test re-framed per-record),
`ScreeningIT`, `NotificationIT`, gateway `TransferProjectionIT`.

## Live verification (gateway×2 + transfers×3 behind both LBs)

- **transfers LB chain:** gateway `TRANSFERS_BASE_URL=http://transfers-lb:80` → nginx (`transfers_pool`
  upstream) → round-robins the 3 `transfers:8080` replicas. Confirmed reachable + config loaded.
- **Consume-side distribution:** the `transfers` consumer group had members on **3 distinct replica IPs**,
  lag 0 — the saga lanes spread across replicas via the group, no extra wiring.
- **Happy path through both LBs:** 75 POSTs → 100 % 202, 0 KO; saga settled; **global ledger Σ = 0**, 0
  unbalanced transfers.

## Results (full-saga open-model write sweep, 24 GiB / 14-core VM, gateway=2 / transfers=3)

> Measured with the batch listeners in place; retained as the **honest demonstrable ceiling** for this
> co-located host. The batch is reverted (see top); these numbers stand because the limiter (total CPU shared
> with the co-located driver) is independent of per-record vs batch, and the ledger posting was already cheap.

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

## Money-safety boundary (non-negotiable, honored — and the loss bug removed)

- **The revert removes the latent loss bug:** per-record means **one record = one tx = one offset**, so a
  committed offset can never outlive a rolled-back DB write. A failure rolls back **and** re-drives exactly
  that record (idempotent via inbox + posting-PK anchor); a poison is isolated to the **DLT**. No money
  guarantee depends on a batch commit boundary, because there is no batch.
- **Overdraft impossible, verified two ways:** `SameSourcePostingOverdraftIT` (8-deep same-source burst,
  per-record → 5 post / 3 reject / balance 0, never negative) **and** the post-bench ledger after hundreds of
  thousands of loaded transfers: **global Σ = 0, 0 unbalanced transfers, 0 negative real-account balances, 0
  duplicate postings.** `ConcurrentDebitIT` green.
- **All money guards unchanged:** the BANK-11 `findByIdForUpdate` source lock, the posting-PK anchor,
  `inbox.firstTime`, Σ=0, `synchronous_commit=on` for the money DBs, `acks=all` + idempotent producer. **No
  sharding.** The fsync efficiency is **Postgres group-commit** (WAL fsyncs batched across concurrent
  per-record commits at the DB level), not an application batch.

## Consequences

- The deployed stack keeps the `transfers-lb` internal nginx; `transfers` runs `--scale transfers=N`. The
  consume-side distributes via the `transfers` consumer group with no code change.
- The money/saga consume-side is **per-record** again — the money-safe shape. The application batch is not to
  be reintroduced on any DB-writing listener while the container is non-transactional and offsets are
  committed outside the listener's DB tx; the fsync win lives at the Postgres group-commit layer instead.
- `BENCHMARKS.md` §11 records the revert, the retained replication, the repurposed gate, the demonstrable
  ceiling, and the 14-core limiter.
- **Phase 2 (next):** hop reduction to cut per-transfer CPU — the honest path past ~1.5k toward 5k, on top of
  the retained replication.
