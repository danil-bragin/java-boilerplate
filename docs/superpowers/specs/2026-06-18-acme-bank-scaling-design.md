# acme-bank scaling — defense-in-depth design

> Extends the production-grade work (BANK-0..14). Goal: increase scalability of the two benchmark-identified ceilings — **per-account writes** (BANK-11 source lock serializes one account) and **saga-settle latency** (bound by partitions × consumer concurrency) — **without weakening any money-safety guarantee**. Driving principle (user): *add scaling by removing contention, never by removing a safety net.*

## 0. Principle: defense-in-depth scaling

Money-safety is non-negotiable: no overdraft, no double/lost/created money, no balance divergence — including edge cases (consumer rebalance, retry, crash, redelivery). We add parallelism by eliminating *contention*, while keeping every existing guard in place.

Rejected: a write-side materialized/running balance + conditional `UPDATE` (option B). It is the simplest big win but (a) introduces a denormalization that can **diverge** from the entries — a new class of "scary" (wrong balance), and (b) violates the standing no-materialization decision. The derived `SUM(entries)` stays the single source of truth for reads and audit.

## 1. Per-account writes — single-writer per account, lock retained as backstop

Today every posting to a source account takes a `PESSIMISTIC_WRITE` lock (BANK-11) so the read-then-write fund check is safe. Under one hot account all debits serialize on that lock → a per-account throughput ceiling (matters at multi-host scale; on a single host Postgres CPU caps first).

**Change:** route the `posting-requested` events **keyed by source `accountId`** (today: keyed by `transferId`) and give the topic **multiple partitions**. Kafka then delivers all postings for one source account to **one partition → one consumer thread → a single writer** for that account. Contention disappears.

**Keep all three guards (defense-in-depth):**
1. **Partition single-writer** — the new mechanism; removes contention in steady state.
2. **The BANK-11 `PESSIMISTIC_WRITE` source lock — NOT removed.** With a single writer it never *waits* (no contender) → ~free, but remains a correctness guarantee for the brief consumer-rebalance window where two consumers may transiently process the same partition. The lock + read-your-writes serializes them.
3. **The BANK-1 posting-PK idempotency anchor + inbox dedup** — unchanged; dedups redelivery / double-process regardless of partition.

Money invariants unchanged: Σ=0 per posting (one consumer writes both legs atomically); destination credits are commutative (independent inserts, derived SUM) so cross-partition concurrent credits to one dest are safe; the BANK-8 per-account asset invariant and operational checks stay. The BANK-12 reconciler is unaffected (it re-emits `posting-requested`, now keyed by account — still idempotent via inbox + anchor).

**Why this is safe even at the rebalance edge:** the only window where two writers could touch one account is a partition reassignment; there, guard #2 (lock) + #3 (anchor) hold exactly as they do today. No steady-state lock contention, full edge safety.

**Honest benchmark caveat:** on the single co-located dev host the source lock is NOT the binding constraint (Postgres CPU / co-location caps first), so this change will NOT show a large full-stack RPS delta there. It raises the per-account *ceiling* for genuine hot accounts at multi-host scale. Proof on this host = a **targeted micro-benchmark** (concurrent distinct-transfer debits on ONE account, measuring lock-wait/throughput before vs after), not full-stack RPS.

## 2. Saga-settle — partitions + concurrency + latency tuning (config; money-safety untouched)

Settle latency (request→COMPLETED) is bound by topic partition count, per-service listener concurrency, and per-hop tx/round-trips.

- **Partition the saga topics** (`transfer-requested`, `transfer-screened`, `posting-requested`, `ledger-posted`, `posting-rejected`, `transfer-completed`, `transfer-failed`) via declared `NewTopic` beans (KafkaAdmin). Keyed by `transferId` (or source account for `posting-requested`) → per-entity ordering preserved.
- **`spring.kafka.listener.concurrency`↑** per consuming service (≤ partition count).
- **Producer/consumer latency tuning**: `linger.ms`, `fetch-max-wait`, `max-poll-records`, batch where safe.
- No money-safety surface touched (keying preserves ordering; inbox dedup + anchors unchanged).

> Re-partitioning caveat: increasing partitions on a keyed topic remaps existing keys. The example deploys fresh (`compose down -v`), so partitions are declared up front — clean. Documented.

## 3. Phases (non-stop, adversarial review each)

- **BANK-15** — per-account write scaling: re-key `posting-requested` by source accountId + multi-partition + accounts listener concurrency; **keep lock + anchor**; money-safety regression (the BANK-11 8-thread overdraft `ConcurrentDebitIT` MUST stay green) + a key-assertion test + a concurrent-two-consumer (rebalance-proxy) safety test.
- **BANK-16** — saga-settle: partition the remaining saga topics + per-service concurrency + producer/consumer latency tuning.
- **BANK-17** — before/after benchmark: a one-account write micro-benchmark (lock-wait elimination) + settle p99 under the tuned config; update `BENCHMARKS.md` + an ADR with the measured delta and the honest single-host framing.

## 4. Safety gate (every phase)

A phase does not close until: the BANK-11 overdraft test is green on the new path; the BANK-12 reconciler invariants hold; Σ=0 / idempotency / asset / operational invariants are intact; ArchUnit + full `gradle build` green. Each change is additive/flag-guarded and reversible (the lock stays → trivial rollback).

## 5. Out of scope
Infra-only scaling (Postgres read replicas, separate DB instances, broker sizing) — noted in BENCHMARKS.md as the next lever beyond the application layer; not built here. Write-side materialized balance — rejected (§0).

## 6. Done criteria
- `posting-requested` keyed by source account, multi-partition; a source account is single-writer; lock + anchor retained; all money-safety tests green + a rebalance-proxy concurrency test.
- Saga topics partitioned, consumer concurrency raised, latency tuned; settle p99 improved (measured).
- Before/after benchmark proves the delta (or honestly reports the single-host ceiling); BENCHMARKS.md + ADR updated.
- No money-safety guarantee weakened; `gradle build` green.
