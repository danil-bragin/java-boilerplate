# 0022 — Posting Strong Consistency: Pessimistic Source-Account Lock

**Date:** 2026-06-18
**Status:** Accepted

## Context

The money-movement saga (ADR-0017) is eventually consistent end-to-end: every hop
is `consume → inbox-dedup → advance → emit`. Exactly one hop actually moves money —
`PostTransferHandler` → `JpaLedger.save` — and it is marked `StronglyConsistent`, so
`TransactionMiddleware` wraps it in a single ACID transaction.

Inside that transaction the fund check is a read-modify-write on a **derived** balance:

```
balance = ledger.balance(source, asset)   // SUM of the account's ledger entries
if (balance < amount) reject INSUFFICIENT_FUNDS
ledger.save(Posting.transfer(...))         // inserts the two legs
```

`TransactionMiddleware`'s `TransactionTemplate` runs at the default isolation,
**READ COMMITTED**, and nothing locked the source account. Two concurrent transfers
with **distinct** transferIds debiting the **same** source therefore both read the
pre-debit balance, both pass the check, and both insert — overdrawing the account
(balance goes negative). The posting primary-key anchor (`posting.transfer_id`) only
dedups the *same* transferId; it does nothing for distinct transfers on one account.

This was confirmed by a regression test (`ConcurrentDebitIT`): 8 threads each debiting
80.00 from a source funded with exactly 100.00 produced **8 postings** and a balance of
**-540.00** before the fix.

Standing constraint: balances are **derived** (SUM of entries), not materialized — so
the fix may not introduce a stored/materialized balance.

## Decision

Acquire a `PESSIMISTIC_WRITE` row lock on the **source** account at the start of the
posting transaction, before the balance check:

- `Accounts.findByIdForUpdate(AccountId)` (out-port) → Spring Data
  `@Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select a from AccountJpaEntity a where a.id = :id")`
  (Postgres `SELECT ... FOR UPDATE`), mapped to the domain `Account` exactly like `findById`.
- `PostTransferHandler` loads the **source** via `findByIdForUpdate` (the destination stays a
  plain `findById`).

The lock is held to transaction end by the surrounding `StronglyConsistent` transaction.
Concurrent debits on one account now **serialize**: the second blocks until the first
commits, then its derived-balance SUM sees the committed debit and correctly rejects.

The account row is used **only as a serialization anchor** — it stores no balance. The
balance stays purely derived; the lock changes nothing about how the balance is computed.

### Idempotency re-check under the lock

Serializing the postings exposed a latent weakness in the same-transferId idempotency.
The posting anchor (`PostingJpaEntity`) has an **assigned** String `@Id`, so
`JpaRepository.save` treats it as non-new and issues a `merge` (SELECT-then-INSERT/UPDATE)
rather than a bare INSERT. Idempotency previously held only because concurrent duplicate
postings *raced* into the INSERT and the unique index rejected the loser. Once the source
lock serializes them, the second duplicate's `merge` finds the committed row and silently
UPDATEs instead of violating the PK — the duplicate would then write a second pair of
ledger entries.

Fix: re-check `existsByTransferId` **after** acquiring the source lock. Under the lock,
any earlier posting for this transferId has committed and is visible, so a redelivered
duplicate short-circuits correctly regardless of timing. The top-of-method check is kept
as a cheap fast path (a pre-lock read for already-committed redeliveries).

### Why only the source is locked (no deadlock)

The destination is only *credited* — there is no overdraft constraint on it — so it needs
no lock. Each posting transaction therefore takes exactly **one** account lock (its own
source). With at most one lock per transaction there is no lock-ordering cycle, so
concurrent cross-direction transfers (A→B and B→A) cannot deadlock. This is asserted by
`ConcurrentDebitIT.crossDirectionTransfersDoNotDeadlock`.

## Alternatives considered

- **`SERIALIZABLE` isolation + retry on serialization failure.** Correct, but pushes
  retry/backoff handling into the command pipeline and serializes more broadly than needed.
  A targeted row lock on the one constrained row is simpler and scoped to the hot account.
- **Materialized/versioned balance column with a `CHECK (balance >= 0)` constraint
  (or optimistic `@Version`).** Rejected: it violates the standing no-materialization
  constraint — balances must stay derived from the double-entry ledger. The row lock keeps
  the balance derived and only serializes writers.

## Consequences

- Concurrent distinct transfers can no longer overdraw a source: exactly one of N racing
  debits succeeds, the derived balance never goes negative, and only the successful
  posting's entries are written. `ConcurrentDebitIT` now reports **1 posted, balance 20.00,
  2 entries, 1 posting** (was 8 posted / -540.00 / 16 entries before the fix).
- Same-transferId idempotency is now robust under serialization (re-check under the lock),
  independent of insert-race timing.
- The money mutation is the single strong-consistency point in an otherwise
  eventually-consistent saga; the derived-balance invariant (ADR-0013) stands.
- **Throughput trade-off:** postings on a hot source serialize. For a money invariant this
  is correct and acceptable — overdraft prevention outranks per-account write parallelism.
