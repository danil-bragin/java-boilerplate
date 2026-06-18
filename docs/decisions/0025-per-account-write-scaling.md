# 0025 — Per-Account Write Scaling: Single-Writer-per-Account (Lock Retained)

**Date:** 2026-06-18
**Status:** Accepted

## Context

ADR-0022 made the posting transaction money-safe by taking a `PESSIMISTIC_WRITE`
lock on the source account before the derived-balance read-modify-write. It closed
with an explicit trade-off: *postings on a hot source serialize* on the database
lock, and "overdraft prevention outranks per-account write parallelism."

That lock is correct and stays. But it means the per-account write ceiling is the
rate at which one source's postings can take the row lock, do the SUM, insert, and
commit — and under the BANK-11 design every consumer thread can pick up *any*
account's `posting-requested` record, so two threads frequently contend on the same
hot account's lock. The lock then does real blocking work on the hot path: thread B
waits on thread A's `SELECT ... FOR UPDATE` even though, in principle, the system
could have arranged for only one thread to ever touch that account.

`posting-requested` was keyed by `transferId` and delivered on a single-partition
topic. So Kafka gave us neither ordering nor write-affinity per account: a hot
account's postings scattered across whatever consumer thread was free, maximizing
lock contention.

Standing constraints (unchanged): balances are **derived** (`SUM(entries)`), never
materialized; no money-safety guarantee from BANK-0..14 may be weakened — in
particular the BANK-11 source lock, the BANK-1 posting-PK anchor, and the inbox
dedup must remain.

## Decision

Make each **source account a single writer** by routing its postings to one
partition / one consumer thread, while **keeping** the lock + anchor + dedup as
defense-in-depth backstops.

1. **Key `posting-requested` by the source account** (not the transferId).
   `TransferExternalizationConfig` routes `PostingRequestedEvent` with
   `.andKey(event.sourceAccountId())`. The other saga events stay keyed by
   transferId. The BANK-12 `SagaReconciler` re-emits the *same* `PostingRequestedEvent`
   type, so its re-drives flow through the *same* route and are also keyed by the
   source account — re-drives land on the same account partition (no code change in
   the reconciler; asserted by `SagaReconcilerIT`).

2. **Make `posting-requested` multi-partition.** `SagaTopicsConfig` declares a
   `NewTopic` with `partitions = ${acme.bank.topics.posting-requested.partitions:6}`,
   created by the auto-configured `KafkaAdmin` on startup. Different accounts hash
   across the partitions; a single account always maps to one partition (its
   single-writer lane).

3. **Consume partitions in parallel.** accounts sets
   `spring.kafka.listener.concurrency = ${ACCOUNTS_LISTENER_CONCURRENCY:6}`
   (≤ partition count) on the default container factory — one thread per partition.
   Within a partition it stays single-threaded.

### Why this is correct (and why the lock still earns its keep)

- **Steady state:** a source-account key → one partition → one consumer thread →
  **a single writer per account**. One account's postings are processed strictly one
  at a time by one thread, so the read-modify-write balance check cannot interleave
  with another posting on the same account. There is no lock *contention* on the hot
  path: the lock is taken uncontended, which is cheap.
- **Rebalance edge:** during a consumer-group rebalance, a partition can be
  transiently owned by two consumers (the old owner has not yet stopped while the new
  owner starts). For a brief window two threads could process the same account's
  postings concurrently. This is exactly the two-writers-on-one-account scenario that
  the **BANK-11 pessimistic source lock** serializes, and that the **BANK-1
  posting-PK anchor** + **inbox dedup** make idempotent. So the partition gives
  throughput; the lock + anchor guarantee *correctness* at the edge. The 8-thread
  `ConcurrentDebitIT` is the direct proof that two writers on one account cannot
  overdraw — it stays green and is the money-safety regression gate.
- **Destination credits are commutative.** A credit is an independent INSERT and the
  balance is a derived SUM, so concurrent credits to one destination from different
  partitions are safe and need no ordering or lock — only the *source* (the
  overdraft-constrained leg) is serialized.
- **Derived balance stays the source of truth.** Nothing here materializes a balance;
  the partition only changes *which thread* runs the same derived-balance check.

### Proof / tests

- `TransferAdvanceIT` + `SagaReconcilerIT` assert the emitted/re-emitted
  `posting-requested` record key is the **source account** (was transferId).
- `PostingRequestedTopicIT` asserts the topic is provisioned multi-partition.
- `ConcurrentPostingConsumerIT` (rebalance-proxy): N distinct-transferId postings for
  one source account, funded for only K, all on one source-keyed partition with
  listener concurrency ≥ 2 → exactly K post, the rest are INSUFFICIENT_FUNDS, the
  source balance lands at exactly 0 (never negative), and every transfer is Σ=0.
- `ConcurrentDebitIT` (BANK-11, unchanged): 8 threads, distinct transfers, one source
  → no overdraft. This is the lock proof for the rebalance edge.

**Which mechanism proves the rebalance-edge safety:** forcing *true* two-thread
contention on a single Kafka key is impractical by design (same key → same partition
→ same single thread in steady state). So the rebalance-edge two-writer safety is
proved by `ConcurrentDebitIT` (direct 8-thread contention on the handler/lock), and
`ConcurrentPostingConsumerIT` asserts the steady-state single-writer *outcome*
end-to-end over Kafka (no overdraft, funded subset posts, rest rejected, Σ=0).

## Alternatives considered

- **Write-side materialized balance + conditional `UPDATE ... WHERE balance >= amount`.**
  Would let any thread post any account without a row lock. Rejected: it materializes
  a balance (violates the standing derived-balance constraint) and introduces
  divergence risk between the stored balance and the double-entry ledger. The
  single-writer routing keeps the balance purely derived.
- **Removing the pessimistic lock** once postings are single-writer-per-account.
  Rejected: it would lose the correctness guarantee at the consumer-rebalance edge
  (two transient consumers on one partition) and on any future change that breaks the
  one-thread-per-account assumption. The lock is uncontended in steady state, so
  keeping it costs almost nothing and remains the last line of defense. Defense in
  depth: never weaken a money guard to chase throughput.
- **More partitions than 6.** Orthogonal — tune via the property. A single hot
  account still maps to one partition (its single-writer lane); more partitions only
  spread *more accounts* in parallel.

## Consequences

- The per-account write ceiling is raised for **multi-host hot accounts**: a hot
  account's postings now run uncontended on one thread instead of fighting for the
  lock against unrelated threads, and unrelated accounts post in parallel across
  partitions.
- **Honest benchmark caveat:** on a single local host the throughput ceiling is the
  Postgres CPU/commit limit for that one account's serialized postings — the partition
  cannot make one account's postings parallel (that would be an overdraft bug). The
  win is realized at scale: multiple consumer hosts, many accounts spread across
  partitions, hot accounts no longer contending on a shared thread pool. The local
  number is not expected to jump.
- **Operational note:** increasing partitions on an *existing* keyed topic remaps
  keys (an account can move partitions), so the partition count is fixed at first
  create. The example deploys fresh (`docker compose down -v`); production sets the
  partition count and a real replication factor at the broker / topic-provisioning
  layer (dev/Redpanda is single-broker, replicas = 1).
- All BANK-0..14 money-safety guarantees stand unchanged: the BANK-11 source lock,
  the BANK-1 posting-PK anchor, and the inbox dedup are all retained as backstops;
  `ConcurrentDebitIT` stays green; the derived-balance invariant (ADR-0013/0022) holds.
