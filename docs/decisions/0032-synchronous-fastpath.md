# 0032 — Synchronous fast-path for eligible transfers; hybrid sync/async, money-safe hop reduction (BANK-22)

**Date:** 2026-06-20
**Status:** Accepted — a synchronous fast-path is added for **eligible** transfers; the async saga is
**retained unchanged** for ineligible/flagged transfers and as the resilience fallback.

## Context

ADR-0031 / BENCHMARKS §11.4 established that the only lever that moves the **5k transfers/s** target is
**cutting the per-transfer CPU**, not adding boxes: at ~5 millicore/transfer end-to-end, 5k ≈ 25 cores —
nearly 2× the 14-core host. The per-transfer cost is dominated by the **6-hop async saga**
(`transfer-requested` → screen → `transfer-screened` → `posting-requested` → post → `ledger-posted` →
`transfer-completed`): 6 Kafka legs (produce+consume+outbox-relay+Avro each), an antifraud screening tx, and
the transfers screening-result tx — all per transfer. For a **small, auto-approvable** transfer, screening is
a foregone conclusion (antifraud auto-approves anything under its 10,000 USD amount-limit) and the
choreography is pure overhead.

## Decision

Add a **hybrid** model. transfers gains a **fast-path branch** in `InitiateTransferHandler`:

1. **Eligibility** = feature flag on **AND** amount ≤ `acme.bank.fast-path.max-amount` (default **1,000 USD**,
   set **conservatively below** the antifraud 10,000 USD limit so an eligible transfer is one antifraud would
   auto-approve anyway), same asset. Cross-asset / over-threshold / flag-off → **slow-path**.
2. **Fast-path:** inline `request()` → `approve()` → `markPosting()`, **persist `POSTING`** (before the call),
   then **one synchronous HTTP `POST /internal/postings`** to accounts — routed to the **EXISTING**
   `PostTransferCommand` / `PostTransferHandler` (the same money mutation the async saga uses). Terminalize
   from the answer; publish the single terminal event via the outbox; return **200 COMPLETED/FAILED**.
3. **Slow-path (unchanged):** ineligible/flag-off → persist `REQUESTED`, publish `TransferRequested`, return
   **202 REQUESTED** — the 6-hop async saga as before.

This collapses the saga to **~1 synchronous hop** for the eligible majority, removing **~5 Kafka legs + the
antifraud screening hop** per transfer (measured: antifraud CPU ~29% → ~1.8% on the eligible sweep).

## The money-safety argument (the crux)

**The money mutation does NOT change.** The fast-path calls the existing `PostTransferHandler` verbatim —
`findByIdForUpdate` source lock + derived-balance check + **Σ=0** + **posting-PK anchor** +
asset/operational invariants. Only **transport** (sync HTTP vs Kafka) and **orchestration** (inline vs
choreographed) change. `ConcurrentDebitIT` + `SameSourcePostingOverdraftIT` stay green; no guard weakened.

**Idempotency, two independent layers.** The gateway **`Idempotency-Key`** dedups the POST (one transfer per
key); the accounts **posting-PK anchor** dedups the posting by `transferId` (one posting even on
retry/double-attempt). **At most one transfer, at most one posting.**

**The timeout-after-send edge is provably double-post-safe — the highest-care part.** The sync client maps
three outcomes to a `SyncPostResult` enum:

- **POSTED / REJECTED** (HTTP 200) — accounts answered; terminalize (COMPLETED / FAILED).
- **`CallNotPermittedException`** (resilience4j circuit OPEN) → **NOT_MADE**: the call was **never
  dispatched**, so no posting happened → **safe async fallback** (re-emit `posting-requested`, stay POSTING,
  202). Money cannot have moved.
- **`ResourceAccessException`** (post-send I/O failure / read timeout) → **UNKNOWN**: the bytes may have
  reached accounts and a posting **may** have committed. **The handler NEVER guesses a terminal** — it leaves
  the transfer **`POSTING`** (persisted *before* the call) for the **BANK-12 `SagaReconciler`**, which queries
  accounts' ledger (`posted(id)`): **posted=true → complete** (the single anchored posting is the only money
  movement — debited exactly once), **posted=false → re-drive only, NEVER fail** (a snapshot does not prove
  money never moved). Any other / ambiguous failure defaults to **UNKNOWN** (leave POSTING) — never "safe to
  retry inline as a terminal".

Proven by `FastPathSafetyIT` (the gate): idempotency (one transfer/posting per key), timeout→reconciler
(no double-post, debited exactly once), circuit-open→async-fallback (no money lost), overdraft→REJECTED.

**Why persist POSTING before the sync call:** a crash mid-call leaves a POSTING row the reconciler resolves
against the ledger — the identical recovery the async path already relies on.

## The slow-path is retained (not replaced)

Ineligible/large/flagged transfers, and the **flag-off** kill switch, keep the **unchanged** async saga. The
saga is also the **fallback** when accounts is unhealthy (circuit open → NOT_MADE → async). This is the
realistic bank pattern: **synchronous authorization for the common small case, asynchronous settlement for
the rest** — not an all-or-nothing rewrite.

## Trade-offs

- **Sync coupling (accepted, bounded):** the fast-path couples transfers→accounts on the request thread. Bounded
  by short connect/read timeouts (500 ms / 2 s) + a circuit breaker; a slow/down accounts degrades to the async
  saga, never to a stuck request thread or a guessed terminal.
- **Two code paths (accepted):** eligibility branch + sync client + the UNKNOWN/NOT_MADE handling are more
  surface than one saga. Mitigated by reusing the **same** posting handler and the **same** reconciler — the
  money logic is not duplicated.
- **Not a 5k claim:** the demonstrable knee is still the **co-located single-host total-CPU wall** (Gatling on
  the same cores). BANK-22 lowers **per-transfer** cost for the eligible majority — the durable finding that
  moves the *real-infra* 5k closer — but does not change the **host** ceiling. See BENCHMARKS §12.

## Consequences

- Eligible transfers complete **synchronously** (~1 hop, 200 COMPLETED); a 12,002-transfer sweep converged
  fully (0 stuck; notifications + gateway projection exact-matched; ledger Σ=0).
- The flag (`acme.bank.fast-path.enabled`) toggles the whole behavior; the threshold is env-tunable.
- Money-safety is fully intact; no sharding; durability retained; `gradle build` green.
