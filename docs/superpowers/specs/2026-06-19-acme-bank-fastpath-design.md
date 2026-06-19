# acme-bank synchronous fast-path (hop reduction) — Design

> Phase-2 throughput lever: cut per-transfer CPU by collapsing the common-case money movement from a 6-hop async saga into a ~1-hop synchronous fast-path, money-safe, so 5k transfers/s fits the hardware. Builds on BANK-0..21.

## 0. Why

A transfer today fans out through ~6 Kafka hops (requested → screened → posting-requested → ledger-posted → completed → notification) = ~6 commits, ~12 Avro ser/deser, ~6 round-trips, ~5 millicore/transfer → 5k ≈ 25 cores (> 14). Replicas spread that cost; they don't cut it. The only lever that cuts the FUNDAMENTAL per-transfer cost is reducing hops. Real banks do exactly this: synchronous authorization for the common case, async for the rest.

## 1. Hybrid model

- **Fast-path (synchronous)** for ELIGIBLE transfers — small amounts that policy auto-approves (no async antifraud needed). The money moves in the request thread; the client gets `200` with the final `COMPLETED` status.
- **Slow-path (async saga)** — UNCHANGED — for ineligible/flagged transfers (large amounts, anything needing full screening) AND as the fast-path's resilience fallback.

### Eligibility
`amount ≤ acme.bank.fast-path.max-amount` (set CONSERVATIVELY at or below the antifraud amount-limit, so a fast-path transfer is one antifraud would auto-approve anyway) AND the feature flag `acme.bank.fast-path.enabled`. Velocity/risk for sub-threshold amounts is low by construction, so the fast-path skips the async screening hop without losing fraud control — anything above the threshold takes the full async screening path.

### Fast-path flow (transfers)
```
POST /v1/transfers (eligible)
 → transfers, in the request thread:
     1. create Transfer, inline-approve (amount ≤ threshold → APPROVED), mark POSTING, persist
     2. SYNCHRONOUS RestClient POST → accounts /internal/postings
          {transferId, source, dest, amount}
          accounts runs the SAME PostTransferHandler: findByIdForUpdate lock +
          derived-balance check + Σ=0 double-entry posting + posting-PK anchor
          → returns posted | rejected(reason)
     3. posted   → Transfer COMPLETED ; rejected → Transfer FAILED(reason)
     4. publish ONE transfer-completed | transfer-failed event (async — notifications + projection)
 → 200 { transferId, status: COMPLETED | FAILED }
```
Hops cut: transfer-requested, transfer-screened, posting-requested, ledger-posted (4 Kafka hops) → one synchronous HTTP call. Only the terminal event stays async.

## 2. Money-safety (the money mutation is UNCHANGED)

The ledger posting still happens in accounts' own transaction with ALL existing guards: `findByIdForUpdate` pessimistic source lock, derived-balance check (no overdraft), Σ=0 double-entry, posting-PK (`transfer_id`) idempotency anchor, per-account asset/operational invariants. Only the TRANSPORT (sync HTTP vs Kafka) and ORCHESTRATION (inline vs choreographed) change.

- **Overdraft / Σ=0 / asset / operational** — unchanged (guards in accounts' tx).
- **Idempotency** — the gateway `Idempotency-Key` dedups the POST; the accounts posting-PK anchor (`transfer_id`) dedups the posting. A retried fast-path POST → at most one transfer, at most one posting.
- **Atomicity across the sync boundary (the critical edge):** transfers marks `POSTING` BEFORE the sync call. Three outcomes:
  - `200 posted/rejected` → transfers completes/fails the transfer (happy path).
  - **connection refused / circuit-open (call NOT made)** → no posting happened → FALL BACK to the async saga (emit `transfer-requested` / `posting-requested`); idempotent.
  - **timeout after send (UNKNOWN if posted)** → do NOT guess; leave the transfer `POSTING`; the BANK-12 reconciler queries accounts `posted(transferId)` → posted → COMPLETED, not-posted-past-timeout → re-drive. The EXISTING reconciler backs this edge — no new mechanism. Even a double-attempt is safe (accounts anchor dedups).
- **Antifraud not lost** — the cheap policy check (amount ≤ threshold) is the fast-path gate; risky/large transfers take the full async screening path. No fraud control is removed; it's routed.
- **Resilience** — a circuit breaker on the sync accounts call falls the fast-path back to the async saga when accounts is unhealthy, so a downstream outage degrades to the decoupled path rather than failing.

## 3. CPU effect

~4 of 6 hops removed → ~12 Avro ser/deser → ~3, ~6 commits → ~2, ~6 round-trips → ~1. Per-transfer cost ~5 → ~2.5–3 millicore → ~5k fits 14 cores. The slow-path keeps its cost (but is the minority of volume).

## 4. Trade-offs (documented)

- Fast-path couples transfers→accounts synchronously (mitigated by the circuit-breaker fallback to async).
- Two code paths (fast + slow) — more to test; both money-safe; the slow-path is the proven existing saga.
- The POST blocks on the money movement (+few ms) but returns the final status (`200`) — better than `202`-then-poll for the common case.
- The example becomes a HYBRID (sync fast-path + async saga) — a more realistic enterprise pattern than pure choreography.

## 5. Phases
- **BANK-22** — the fast-path: accounts synchronous `/internal/postings` endpoint (reusing `PostTransferHandler`); transfers fast-path branch (eligibility + inline-approve + sync post + terminal event + circuit-breaker fallback); reconciler edge confirmed; money-safety gate (`ConcurrentDebitIT` + new fast-path idempotency/atomicity/fallback tests); re-bench the per-transfer CPU drop + new knee.

## 6. Out of scope
Merging accounts' ledger into transfers (a bigger DDD restructure) — the sync-cross-service post keeps the service boundary. Full inline antifraud (ML/external) — stays async on the slow-path.

## 7. Done criteria
- Eligible transfers complete synchronously in ~1 hop with `200 COMPLETED`; ineligible/flagged/fallback use the unchanged async saga.
- Money mutation unchanged (lock + Σ=0 + anchor + idempotency); the sync-failure edge resolves via the existing reconciler; circuit-breaker falls back to async.
- Per-transfer CPU measurably down; the write knee up (re-benched); money-safety tests green; no sharding; durability retained.
