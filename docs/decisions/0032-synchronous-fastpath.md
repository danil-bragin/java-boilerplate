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
   set **conservatively below** the antifraud 10,000 USD limit so an eligible transfer is one the antifraud
   **amount-limit** rule would auto-approve), same asset, **AND** the source is **under the per-source
   fast-path velocity cap** (`acme.bank.fast-path.max-velocity-per-source`, default **5**, aligned to the
   antifraud `maxVelocity`). Cross-asset / over-threshold / flag-off / **over-velocity** → **slow-path**.
   Eligibility is **NOT** full-antifraud equivalence: it implies only that the **amount-limit** rule
   auto-approves; the **velocity** rule is bypassed by skipping screening and is **re-bounded** by the
   per-source cap (see *The velocity bypass*, below), **not** by an equivalent antifraud screen.
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

**Idempotency, three layers (one is a DB invariant).** (1) The edge **`Idempotency-Key`** filter replays the
cached response for a repeated key (one transfer per key) — but this is an **in-memory** store, so it does
NOT hold across a bypassed filter, a cross-instance request, or a **replicated** transfers. (2) transfers
therefore **requires** the `Idempotency-Key` server-side and **derives a deterministic `transferId` from it**
(`UUID.nameUUIDFromBytes(key)`) instead of `randomUUID()`: two requests with the same key — even reaching
**different** transfers instances — mint the **SAME** `transferId`. (3) The accounts **posting-PK anchor**
dedups the posting by `transferId`. So even when the filter is bypassed/replicated, the same key → same
`transferId` → the anchor (a **DB invariant**, not an in-memory cache) dedups → **at most one transfer, at
most one posting**. Correctness rests on the anchor, not solely on the filter.

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
`IdempotencyKeyIT` proves the keyless→400 and same-key→one-transfer/one-posting (filter disabled) anchor path.

**Why persist POSTING before the sync call:** a crash mid-call leaves a POSTING row the reconciler resolves
against the ledger — the identical recovery the async path already relies on.

## The velocity bypass (and how the per-source cap re-bounds it)

The fast-path **skips antifraud screening entirely**, so a fast-pathed transfer records **no
`screening_decision` row**. antifraud's **velocity** rule counts a source's prior screening decisions, so it
is **BLIND** to fast-pathed transfers: without a ceiling, a fraudster could run **unbounded N × ≤threshold**
transfers from one source with **no velocity limit** — a fraud-control regression, even though the *money*
core (lock + Σ=0 + anchor) stays sound.

This is closed **without re-adding a Kafka hop** by a **per-source fast-path velocity guard in transfers**:
before taking the fast-path, transfers counts the source's recent transfers in its **OWN DB**
(`count(*) from transfer where source_account_id = ? and created_at > now() − window`, indexed by
`(source_account_id, created_at)`). If the count **≥** `acme.bank.fast-path.max-velocity-per-source`
(default **5**, aligned to the antifraud `maxVelocity`), the transfer is **no longer fast-path-eligible** →
it is routed to the **async slow-path**, which **does** record a `screening_decision` and **does** enforce
the velocity rule. So the fast-path is **self-limiting for fraud**: a normal low-velocity source stays fast;
a high-velocity (potentially fraudulent) source is **forced onto the full screened path** it would have
bypassed. Proven by `FastPathVelocityIT` (the routing switch: `cap` synchronous COMPLETEs, then the
`cap+1`-th from that source → 202 REQUESTED / async screening).

## The slow-path is retained (not replaced)

Ineligible/large/flagged transfers, and the **flag-off** kill switch, keep the **unchanged** async saga. The
saga is also the **fallback** when accounts is unhealthy (circuit open → NOT_MADE → async). This is the
realistic bank pattern: **synchronous authorization for the common small case, asynchronous settlement for
the rest** — not an all-or-nothing rewrite.

## Trade-offs

- **Sync coupling + in-tx connection-hold (accepted, bounded):** the fast-path couples transfers→accounts on
  the request thread, and the sync POST runs **inside** the transfers `StronglyConsistent` DB tx — so it holds
  a **Hikari connection for the call's whole duration**. Under accounts slowness this would **pin connections**.
  Bounded by **tight** connect/read timeouts (**200 ms / 500 ms**) + **max-attempts 1** (no retry
  amplification) + the circuit breaker: the worst-case connection-hold is small, and persistent slowness trips
  the breaker fast → **NOT_MADE → async fallback**, so connections are not held on a down accounts. We keep the
  **single-tx atomicity** (status update + outbox event commit together) and bound the hold rather than split
  into two transactions. This is the **throughput trade-off** of the synchronous fast-path. Verified by
  `AccountsSyncTimeoutIT` (tight timeouts; down accounts trips the breaker to NOT_MADE without pinning).
- **Velocity bypass, re-bounded by a per-source cap (accepted):** the fast-path skips antifraud screening, so
  the antifraud **velocity** rule is blind to fast-pathed transfers (the **amount-limit** rule is honored by
  the eligibility threshold; the velocity rule is **not**). Re-bounded — **not** by full antifraud equivalence
  — by the per-source fast-path velocity cap (`max-velocity-per-source`, default 5): a source over the cap is
  routed to the async **screened** slow-path. See *The velocity bypass* above; proven by `FastPathVelocityIT`.
- **Two code paths (accepted):** eligibility branch + sync client + the UNKNOWN/NOT_MADE handling are more
  surface than one saga. Mitigated by reusing the **same** posting handler and the **same** reconciler — the
  money logic is not duplicated.
- **Not a 5k claim:** the demonstrable knee is still the **co-located single-host total-CPU wall** (Gatling on
  the same cores). BANK-22 lowers **per-transfer** cost for the eligible majority — the durable finding that
  moves the *real-infra* 5k closer — but does not change the **host** ceiling. See BENCHMARKS §12.

## Consequences

- Eligible transfers complete **synchronously** (~1 hop, 200 COMPLETED); a 12,002-transfer sweep converged
  fully (0 stuck; notifications + gateway projection exact-matched; ledger Σ=0).
- The flag (`acme.bank.fast-path.enabled`) toggles the whole behavior; the threshold is env-tunable. New
  env-tunable knobs: `acme.bank.fast-path.max-velocity-per-source` (+ `velocity-window`) bound the fraud
  velocity; the tight `accounts.connect-timeout`/`read-timeout` + `accounts-sync` retry `max-attempts`
  bound the in-tx connection-hold.
- transfers **requires** `Idempotency-Key` and derives a **deterministic** `transferId` from it, so the
  posting-PK anchor keeps it **double-post-safe even when transfers is replicated** (the in-memory
  idempotency filter alone does not survive a bypassed filter / cross-instance / replicated transfers).
- Money-safety is fully intact; no sharding; durability retained; `gradle build` green.
