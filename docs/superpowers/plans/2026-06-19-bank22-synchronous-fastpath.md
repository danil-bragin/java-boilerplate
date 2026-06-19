# BANK-22: synchronous fast-path (hop reduction) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** Add a money-safe synchronous fast-path for eligible (small, auto-approved) transfers that collapses the 6-hop async saga into ~1 synchronous hop — cutting per-transfer CPU toward a 5k-fit — while leaving the async saga unchanged for ineligible/flagged transfers and as the resilience fallback.

**Architecture:** Hybrid. transfers gets a fast-path branch: eligible transfer → inline-approve → SYNCHRONOUS RestClient post to accounts `/internal/postings` (the SAME `PostTransferHandler`: lock + Σ=0 + anchor) → COMPLETED/FAILED → one terminal event → `200`. A circuit breaker falls the fast-path back to the async saga when accounts is unhealthy; a timeout-after-send leaves the transfer `POSTING` for the BANK-12 reconciler. The money mutation is unchanged.

**Tech Stack:** Java 21, Spring Boot 3.5.6, Spring `RestClient` + Resilience4j, the existing cqrs `PostTransferHandler`, acme-featureflags, the BANK-12 reconciler, Testcontainers.

> Spec: `docs/superpowers/specs/2026-06-19-acme-bank-fastpath-design.md`. Builds on BANK-0..21.
> **Environment (every task):** work from `/Users/npden4ik/Projects/java-boilerplate`, on `master`; system `gradle` (8.14); JDK 21; Docker up (Postgres/Redpanda cached). `gradle <module>:spotlessApply` before each commit.

## Money-safety gate (every task)
The money mutation is UNCHANGED — accounts posting keeps `findByIdForUpdate` lock + derived-balance check + Σ=0 + posting-PK anchor + asset/operational invariants. `ConcurrentDebitIT` + `SameSourcePostingOverdraftIT` stay green. New fast-path tests prove idempotency (one posting per Idempotency-Key/transferId), the atomicity edge (timeout → reconciler completes, never double-posts), and the circuit-breaker fallback (accounts down → async saga, no money lost). `synchronous_commit=on` money DBs, `acks=all`, no sharding.

---

## Task 1: accounts — synchronous posting endpoint

**Files:** `accounts/.../adapter/in/web/InternalPostingController.java` (extend — it already has `GET /internal/postings/{id}` from BANK-12), DTOs, `InternalPostingPostIT.java`.

- [ ] **Step 1: failing IT** `InternalPostingPostIT` (`RANDOM_PORT`, Postgres): `POST /internal/postings {transferId, sourceAccountId, destinationAccountId, amount:{value,asset}}` for a funded source → 200 `{transferId, status:"POSTED"}`, ledger Σ=0, source debited; insufficient funds → 200 `{status:"REJECTED", reason:"INSUFFICIENT_FUNDS"}`, no entries; a REPEAT POST with the same transferId → still POSTED, exactly ONE posting (anchor dedups). No bearer required on `/internal/**` (network-segmented).
- [ ] **Step 2: run, FAIL.**
- [ ] **Step 3:** Add `POST /internal/postings` to `InternalPostingController` → maps to the EXISTING `PostTransferCommand` via the pipeline → returns the `PostTransferResult` (posted/rejected+reason) as a DTO. Reuse `PostTransferHandler` verbatim (lock + Σ=0 + anchor + idempotency) — do NOT duplicate money logic. The endpoint is synchronous and idempotent (the posting-PK anchor makes a retry a no-op returning POSTED).
- [ ] **Step 4: run, PASS** — + `PostTransferIT`, `PostingFlowIT`, `ConcurrentDebitIT`, `SameSourcePostingOverdraftIT` green (the handler is shared/unchanged).
- [ ] **Step 5: commit**
```bash
gradle :examples:acme-bank:accounts:spotlessApply
git add examples/acme-bank/accounts
git commit -m "feat(accounts): synchronous POST /internal/postings (reuses PostTransferHandler; idempotent via posting-PK anchor)"
```

---

## Task 2: transfers — fast-path branch (eligibility + inline approve + sync post) TDD

**Files:** `transfers/.../adapter/out/posting/AccountsPostingSyncClient.java` (new RestClient + resilience4j), `transfers/.../application/InitiateTransferHandler.java` (fast-path branch), `transfers/.../domain/Transfer.java` (confirm REQUESTED→APPROVED→POSTING→COMPLETED/FAILED transitions usable synchronously), `FastPathProperties.java`, `application.yaml` (flag + threshold + resilience4j `accounts-sync` + accounts base url), `FastPathIT.java`.

- [ ] **Step 1: failing IT** `FastPathIT` (`RANDOM_PORT`, Postgres+Redpanda; accounts stubbed via a `@Primary` fake `AccountsPostingSyncClient` OR WireMock for the happy/reject/down cases):
  - eligible (amount ≤ threshold), accounts stub returns POSTED → `POST /v1/transfers` returns **200 `{status:"COMPLETED"}`**; the transfer row is COMPLETED; a `transfer-completed` event is emitted (assert on the topic).
  - eligible, accounts stub returns REJECTED(INSUFFICIENT_FUNDS) → 200 `{status:"FAILED", failureReason:"INSUFFICIENT_FUNDS"}`; transfer FAILED; `transfer-failed` emitted.
  - INELIGIBLE (amount > threshold) → 202 `{status:"REQUESTED"}` + `transfer-requested` emitted (the unchanged async slow-path); accounts sync client NOT called.
  - feature flag OFF → always slow-path (202 REQUESTED) regardless of amount.
- [ ] **Step 2: run, FAIL.**
- [ ] **Step 3:** Implement:
  - `FastPathProperties`: `acme.bank.fast-path.enabled` (default true), `acme.bank.fast-path.max-amount` (a Money/decimal, set ≤ the antifraud amount-limit — e.g. "1000.00").
  - `AccountsPostingSyncClient`: Spring `RestClient` → `${ACCOUNTS_BASE_URL}/internal/postings`, `@CircuitBreaker(name="accounts-sync", fallbackMethod=...)` + `@Retry` (ignore 4xx). Returns a typed result {posted|rejected+reason} OR signals fallback. The fallback method distinguishes `CallNotPermittedException` (circuit open — call NOT made) from a post-send timeout (`ResourceAccessException` — UNKNOWN) — expose this to the handler (e.g. a result enum POSTED/REJECTED/NOT_MADE/UNKNOWN).
  - `InitiateTransferHandler` fast-path branch: if `enabled && amount ≤ maxAmount`:
    1. `Transfer.request(...)` → `approve()` → `markPosting()`; `transfers.save(transfer)` (persist POSTING BEFORE the sync call — critical for the reconciler edge).
    2. call `accountsPostingSyncClient.post(...)`:
       - POSTED → `transfer.complete()`; save; publish `TransferCompletedEvent`; return COMPLETED (200).
       - REJECTED(reason) → `transfer.fail(reason)`; save; publish `TransferFailedEvent`; return FAILED (200).
       - NOT_MADE (circuit open / connection refused — no posting happened) → fall back to async: publish `TransferRequestedEvent` (the slow-path takes over; transfer goes back through screening→posting); return REQUESTED (202). (Reset status appropriately so the async path's transitions are legal — e.g. keep REQUESTED and let the saga re-drive, OR re-emit posting-requested from POSTING; choose the path whose transitions are legal and idempotent.)
       - UNKNOWN (timeout after send) → leave transfer POSTING; return ACCEPTED (202 `{status:"POSTING"}`); the BANK-12 reconciler resolves (queries accounts `posted(id)` → complete/re-drive). Do NOT complete or fail here (could be wrong).
  - else (ineligible / flag off) → the EXISTING slow-path (publish `TransferRequestedEvent`, 202 REQUESTED) — unchanged.
  - The transfers REST response carries the final status; the gateway passes it through.
- [ ] **Step 4: run, PASS** — `FastPathIT` + all transfers ITs (`TransferApiIT`, `TransferAdvanceIT`, `TransferQueryIT`, `SagaReconcilerIT`) green.
- [ ] **Step 5: commit**
```bash
gradle :examples:acme-bank:transfers:spotlessApply
git add examples/acme-bank/transfers
git commit -m "feat(transfers): synchronous fast-path for eligible transfers (inline approve + sync post + circuit-breaker fallback to async)"
```

---

## Task 3: money-safety — idempotency + atomicity-edge + fallback tests (the gate)

**Files:** `transfers/.../FastPathSafetyIT.java` (new), maybe an accounts assertion.

- [ ] **Step 1:** `FastPathSafetyIT` — the money-safety gate for the fast-path (real Postgres+Redpanda, real accounts via the running context or a controllable stub):
  - **Idempotency:** two `POST /v1/transfers` with the SAME `Idempotency-Key` (through the gateway idempotency filter) + identical body → ONE transfer, ONE posting, the second replays the 200. AND a direct double sync-post for the same transferId → exactly one posting (accounts anchor). Source debited exactly once.
  - **Atomicity edge (timeout → reconciler, never double-post):** simulate accounts posting SUCCEEDS but transfers gets UNKNOWN (timeout) → transfer stays POSTING → run the reconciler (`reconcileOne`) → it queries accounts `posted=true` → completes the transfer. Assert the source was debited EXACTLY ONCE (no double-post) and the transfer ends COMPLETED. Then a separate case: accounts did NOT post + timeout → reconciler `posted=false` → re-drives (re-emit posting-requested), eventually posts once.
  - **Fallback (accounts down → async, no money lost):** force the circuit OPEN (accounts unreachable) → fast-path returns 202 REQUESTED + the async saga drives the transfer to COMPLETED via the normal hops once accounts is back; assert Σ=0, one posting, no double-spend.
  - **Overdraft on fast-path:** an eligible transfer for more than the (small) balance → accounts REJECTED INSUFFICIENT_FUNDS → transfer FAILED, no entries. (Eligible amounts are small but a near-empty account still can't overdraw.)
- [ ] **Step 2: run, PASS.** Plus `ConcurrentDebitIT` + `SameSourcePostingOverdraftIT` green (unchanged money path).
- [ ] **Step 3: commit**
```bash
gradle :examples:acme-bank:transfers:spotlessApply
git add examples/acme-bank/transfers
git commit -m "test(transfers): fast-path money-safety gate — idempotency, timeout→reconciler (no double-post), circuit-open→async fallback, overdraft rejected"
```

---

## Task 4: re-bench the per-transfer CPU drop + new knee

- [ ] **Step 1:** Bring up the stack (bench overrides). Drive a WRITE sweep where the load amounts are fast-path-ELIGIBLE (≤ threshold) so the fast-path is exercised. Measure: max sustainable write QPS @ 0% err, p99, the per-transfer CPU (compare to the ~5 millicore / ~2.2k baseline — expect a meaningful drop / higher knee), and the new saturating resource. Also run an INELIGIBLE-amount sweep to confirm the slow-path still works at its prior numbers.
- [ ] **Step 2:** Confirm the saga still settles for the slow-path + the fast-path terminal events (notifications + projection converge). Tear down (`down -v`).
- [ ] **Step 3:** Honest report: the CPU-per-transfer reduction, the new write knee on this host (still co-located with Gatling — the demonstrable ceiling caveat stands, but the per-transfer cost drop is the durable finding), how much closer to 5k, and the limiter.

---

## Task 5: BENCHMARKS.md + ADR

**Files:** `examples/acme-bank/BENCHMARKS.md` (§12), `docs/decisions/0032-synchronous-fastpath.md`.

- [ ] **Step 1:** `BENCHMARKS.md` §12 "Synchronous fast-path (BANK-22)": the hop reduction (6→~1 for eligible), the measured per-transfer CPU drop, the new knee, and the honest single-host ceiling. ADR `0032-synchronous-fastpath.md`: the hybrid model, eligibility policy, the money-safety argument (unchanged mutation + idempotency + reconciler edge + circuit-breaker fallback), the slow-path retained, trade-offs (sync coupling, two paths), and that this is the realistic bank pattern (sync auth + async for the rest).
- [ ] **Step 2:** `gradle build` green. Commit.
```bash
git add examples/acme-bank/BENCHMARKS.md docs/decisions/0032-synchronous-fastpath.md
git commit -m "docs(benchmarks): synchronous fast-path results + ADR 0032 (hybrid sync/async, money-safe hop reduction)"
```

---

## Done criteria for BANK-22

- Eligible transfers complete synchronously (~1 hop, 200 COMPLETED); ineligible/flagged use the unchanged async saga; flag toggles it.
- Money mutation unchanged (lock + Σ=0 + anchor + idempotency); fast-path idempotency + atomicity-edge (timeout→reconciler, no double-post) + circuit-open→async-fallback all tested and green; `ConcurrentDebitIT`/`SameSourcePostingOverdraftIT` green.
- Re-bench shows the per-transfer CPU drop + a higher knee; honest single-host caveat retained.
- No sharding; durability retained; `gradle build` green; BENCHMARKS §12 + ADR 0032.

---

## Self-review notes

- **Money-safety (the crux):** the fast-path changes TRANSPORT + ORCHESTRATION, NOT the mutation. accounts posting keeps every guard. Idempotency = gateway Idempotency-Key + posting-PK anchor. The dangerous timeout-after-send edge is resolved by the EXISTING BANK-12 reconciler (POSTING + `posted(id)` query) — NEVER guess-complete/guess-fail on UNKNOWN. Circuit-open (call not made) → safe async fallback. The gate test (Task 3) must prove no double-post on the timeout edge and no money loss on the fallback.
- **Transition legality:** the fast-path drives REQUESTED→APPROVED→POSTING→COMPLETED/FAILED synchronously — the same transitions the async saga makes; confirm the Transfer state machine allows them in-thread (it does). The NOT_MADE fallback must leave the transfer in a state whose async re-drive transitions are legal + idempotent (inbox + anchor dedup).
- **No placeholders:** eligibility (amount ≤ threshold + flag), the sync client result enum (POSTED/REJECTED/NOT_MADE/UNKNOWN), the per-outcome handling, the gate tests.
- **Honesty:** the demonstrable knee is still host-bound (Gatling co-located); the DURABLE finding is the per-transfer CPU drop (hops cut), which is what moves the real-infra 5k within reach. Report the CPU/transfer delta, not just the laptop QPS.
- **Risk:** the highest-care part is the timeout-after-send edge — getting UNKNOWN handling wrong = double-post or stuck money. The reconciler must reliably resolve it; the gate test is non-negotiable. If the sync client can't cleanly distinguish NOT_MADE vs UNKNOWN, treat ambiguous as UNKNOWN (leave POSTING for the reconciler) — never as "safe to retry inline".
