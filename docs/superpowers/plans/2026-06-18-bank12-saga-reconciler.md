# BANK-12: stuck-saga reconciler — eventual-grade hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** Close the eventual-consistency safety gap — a transfer stuck in a non-terminal state (a lost event / a service that was down past retries → DLT) currently hangs forever. Add a scheduled, ShedLock-guarded reconciler in `transfers` that detects stuck sagas and drives them to a terminal state **money-safely**: re-drive pre-money states (re-emit, idempotent), reconcile the money state (POSTING) against accounts' ledger (the source of truth), and hard-timeout to `FAILED(SAGA_TIMEOUT)` only when it is confirmed no money moved. Plus an `EventuallyConsistent` marker (symmetry with `StronglyConsistent`) and a DLT metric.

**Architecture:** The money mutation is already strongly consistent (BANK-11). This phase makes the *coordination* self-healing. A `@Scheduled @SchedulerLock` sweep in transfers finds non-terminal transfers older than thresholds and applies a tiered, money-safe policy. The POSTING state (where money may have moved) is never blindly failed — the reconciler asks accounts "is transfer X posted?" via an internal query (the ledger is the truth) and either completes (posting found → ledger-posted was lost) or, if confirmed not posted past the hard timeout, fails. Convergence is guaranteed; money-safety is preserved.

**Tech Stack:** Java 21, Spring Boot 3.5.6, Spring `@Scheduled`, ShedLock (`@SchedulerLock`, already a starter), Spring `RestClient` + Resilience4j, Micrometer (DLT counter), acme-cqrs marker, Testcontainers.

> Found in the strong-vs-eventual analysis; the eventual-side counterpart to BANK-11. Builds on BANK-0..11.
> **Environment (every task):** work from `/Users/npden4ik/Projects/java-boilerplate`, on `master`; system `gradle` (8.14) NEVER `./gradlew`; JDK 21; Docker up (Postgres/Redpanda cached). `gradle <module>:spotlessApply` before each commit. Bank service convention = `acme.bank-service-conventions` + `alias(libs.plugins.spring.boot)`.

---

## Task 1: accounts — internal posting-status query (money truth)

**Files:** `accounts/.../adapter/in/web/InternalPostingController.java`, accounts security config (permit `/internal/**` — network-internal), `PostingStatusIT.java`.

- [ ] **Step 1: failing IT** `PostingStatusIT` (`RANDOM_PORT`, Postgres): after a successful `PostTransferCommand` for `transferId=t1`, `GET /internal/postings/t1` → 200 `{"transferId":"t1","posted":true}`; an unposted id → 200 `{"posted":false}` (or 404 — pick one and be consistent; prefer **200 with a boolean** so the client distinguishes "definitely not posted" from a transport error). No bearer required on `/internal/**`.
- [ ] **Step 2: run, FAIL.**
- [ ] **Step 3:** Add `InternalPostingController` `GET /internal/postings/{transferId}` returning `{transferId, posted}` where `posted = ledger.existsByTransferId(transferId)`. Permit `/internal/**` in the accounts security config (these are service-to-service, network-segmented — NOT exposed at the gateway edge, which only proxies `/v1/**`). Add a short javadoc + a note that `/internal/**` must be network-restricted in deployment (it is — compose does not publish accounts to the host; only the gateway is published).
- [ ] **Step 4: run, PASS.**
- [ ] **Step 5: commit**
```bash
gradle :examples:acme-bank:accounts:spotlessApply
git add examples/acme-bank/accounts
git commit -m "feat(accounts): internal posting-status query (/internal/postings/{id}) — money source of truth for reconciliation"
```

---

## Task 2: transfers — reconciliation client + Transfer terminal/timeout transitions

**Files:** `transfers/.../adapter/out/reconcile/AccountsPostingClient.java` (RestClient + resilience4j), `transfers/.../domain/Transfer.java` (a `timeOut()`/`failBySystem` transition + `markCompleted` reuse), `transfers/src/main/resources/application.yaml` (accounts base url + resilience4j `accounts-reconcile` + reconciler thresholds), unit test `TransferTest` additions.

- [ ] **Step 1: failing test** `TransferTest`: a transfer in `POSTING` can `complete()` (already exists from BANK-5) and a transfer in a PRE-money state (REQUESTED/APPROVED) can transition to `FAILED` with reason `SAGA_TIMEOUT` via a new `timeOut()` method; `timeOut()` from `POSTING` is NOT allowed directly (POSTING must be reconciled, not blindly timed out) — assert it throws `IllegalStateException`.
- [ ] **Step 2: run, FAIL.**
- [ ] **Step 3:** Add `Transfer.timeOut()` — guarded transition allowed only from REQUESTED/SCREENING/APPROVED → FAILED, reason `SAGA_TIMEOUT`; throws from POSTING/terminal. (Reuse the existing `fail(reason)` if it already guards; otherwise add the guard.) Keep `complete()` (POSTING→COMPLETED) and `fail(reason)` as-is.
- [ ] **Step 4:** `AccountsPostingClient` — Spring `RestClient` to accounts (`${ACCOUNTS_BASE_URL:http://localhost:8081-or-the-accounts-port}/internal/postings/{id}`), `@CircuitBreaker(name="accounts-reconcile")` + `@Retry` with `ignore-exceptions` for 4xx; returns `Optional<Boolean> posted` (empty on transport failure → reconciler skips this round, does NOT fail the transfer on a transport error — money-safety). Add the resilience4j `accounts-reconcile` instance + thresholds to `application.yaml`:
```yaml
acme:
  bank:
    reconciler:
      enabled: ${RECONCILER_ENABLED:true}
      nudge-after: ${RECONCILER_NUDGE_AFTER:PT30S}
      fail-after: ${RECONCILER_FAIL_AFTER:PT5M}
      batch-size: 100
```
- [ ] **Step 5: run, PASS** (`TransferTest`). 
- [ ] **Step 6: commit**
```bash
gradle :examples:acme-bank:transfers:spotlessApply
git add examples/acme-bank/transfers
git commit -m "feat(transfers): SAGA_TIMEOUT transition + accounts reconciliation client (money-truth query)"
```

---

## Task 3: transfers — the scheduled reconciler (ShedLock) — TDD

**Files:** `transfers/.../application/SagaReconciler.java`, a query on the transfers repo (non-terminal + `updated_at < cutoff`), enable scheduling + ShedLock, `SagaReconcilerIT.java`.

- [ ] **Step 1:** Repo query: `findStuck(List<String> nonTerminalStatuses, Instant nudgeCutoff, Pageable)` returning transfers whose status ∈ {REQUESTED,SCREENING,APPROVED,POSTING} and `updatedAt < nudgeCutoff`.
- [ ] **Step 2: failing IT** `SagaReconcilerIT` (Postgres + Redpanda; you can invoke the reconciler method directly rather than waiting for the schedule). Seed transfers with an OLD `updated_at` and assert the tiered policy:
  - **(a) POSTING + accounts says posted** (stub `AccountsPostingClient` to return `true`): reconciler → transfer COMPLETED + emits `transfer-completed`. (Recovery of a lost `ledger-posted`.)
  - **(b) POSTING + accounts says NOT posted + age > nudge but < fail**: reconciler RE-EMITS `posting-requested` (the downstream will post; idempotent via accounts inbox), transfer stays POSTING. Assert a `posting-requested` record was produced.
  - **(c) POSTING + accounts says NOT posted + age > fail**: reconciler → transfer FAILED(`SAGA_TIMEOUT`) + emits `transfer-failed`. (Money confirmed not moved → safe to fail.)
  - **(d) REQUESTED + age > nudge but < fail**: RE-EMIT `transfer-requested` (re-screen; idempotent). 
  - **(e) APPROVED/REQUESTED + age > fail**: FAILED(`SAGA_TIMEOUT`) + `transfer-failed` (pre-money, always safe).
  - **(f) accounts query transport failure (stub throws/empty) on a POSTING transfer**: reconciler does NOT change the transfer (skips this round) — assert status unchanged. (Never fail money on a transport error.)
- [ ] **Step 3: run, FAIL.**
- [ ] **Step 4:** Implement `SagaReconciler`:
  - `@Scheduled(fixedDelayString = "${acme.bank.reconciler.fixed-delay:PT15S}")` `@SchedulerLock(name="transfer-saga-reconciler", lockAtMostFor="PT2M")` on a `reconcile()` method (guard the whole class with `@ConditionalOnProperty(acme.bank.reconciler.enabled=true)`). Extract the per-transfer logic into a package-visible `reconcileOne(Transfer)` the IT calls directly.
  - For each stuck transfer:
    - if `POSTING`: `posted = accountsPostingClient.posted(transferId)`. If empty (transport error) → skip. If `true` → `transfer.complete()` + publish `TransferCompletedEvent` + save. If `false` → if age > failAfter → `transfer.fail("SAGA_TIMEOUT")` + publish `TransferFailedEvent`; else (age > nudgeAfter) → re-publish `PostingRequestedEvent` (re-drive accounts).
    - if pre-money (`REQUESTED`/`SCREENING`/`APPROVED`): if age > failAfter → `transfer.timeOut()` (→FAILED SAGA_TIMEOUT) + publish `TransferFailedEvent`; else → re-publish the appropriate upstream event (`TransferRequestedEvent` for REQUESTED, `PostingRequestedEvent` for APPROVED) to re-drive.
  - Each `reconcileOne` runs in its own transaction (so a save+publish is atomic and one bad row doesn't abort the batch); the publishes go through the existing Modulith outbox externalization (re-published domain events → Kafka; downstream inbox dedups). Idempotency: re-emitting is safe because every consumer is inbox-deduped and the posting is PK-anchored.
  - Enable `@EnableScheduling` (if not already) and ensure ShedLock is configured (the repo has a ShedLock starter — wire its `LockProvider` over the transfers datasource; reuse the existing pattern if another module already uses `@SchedulerLock`).
- [ ] **Step 5: run, PASS** (`SagaReconcilerIT` all 6 cases).
- [ ] **Step 6: commit**
```bash
gradle :examples:acme-bank:transfers:spotlessApply
git add examples/acme-bank/transfers
git commit -m "feat(transfers): money-safe stuck-saga reconciler (ShedLock; re-drive pre-money, reconcile POSTING vs ledger, SAGA_TIMEOUT only when no money moved)"
```

---

## Task 4: EventuallyConsistent marker + DLT metric

**Files:** `starters/acme-cqrs-spring-boot-autoconfigure/.../EventuallyConsistent.java`, apply it on the saga listeners' command/handler surface where appropriate (documentation-grade), a Micrometer counter in the DLT path (acme-messaging/outbox or the bank DLT handler).

- [ ] **Step 1:** Add `EventuallyConsistent` marker interface in acme-cqrs (mirror `StronglyConsistent`) with javadoc: marks a command/flow whose effects converge asynchronously (no surrounding tx guarantee; relies on outbox/inbox + reconciliation). `TransactionMiddleware` treats it like an unmarked command (no tx) — but the marker documents intent and enables future routing. Add a unit/doc test asserting an `EventuallyConsistent` command runs without a transaction wrapper.
- [ ] **Step 2:** DLT metric: where the bank DLT handler routes a poisoned message (the `<topic>-dlt` path from BANK-2/3), increment a Micrometer counter `acme.saga.dlt` tagged by topic, and log at WARN. This surfaces stuck/poisoned events for alerting (the reconciler handles silent hangs; the DLT metric handles poison). If the DLT handler lives in a starter, add it there; if per-service, add to the bank services' DLT config. Add a test asserting the counter increments on a DLT route (or at least that the handler is wired).
- [ ] **Step 3: run, PASS.**
- [ ] **Step 4: commit**
```bash
gradle :starters:acme-cqrs-spring-boot-autoconfigure:spotlessApply
git add starters/acme-cqrs-spring-boot-autoconfigure examples/acme-bank
git commit -m "feat(cqrs,bank): EventuallyConsistent marker + DLT metric (acme.saga.dlt) for alerting"
```

---

## Task 5: README + ADR + full build

**Files:** `examples/acme-bank/README.md` (reconciler section), `docs/decisions/0023-saga-reconciliation.md`.

- [ ] **Step 1:** README "Self-healing / reconciliation" section: the reconciler's tiered money-safe policy, thresholds (`nudge-after`/`fail-after`), ShedLock single-runner, the `/internal/postings/{id}` truth query, the `acme.saga.dlt` metric, and how strong (BANK-11) + eventual (BANK-12) together make the saga both money-correct and self-converging.
- [ ] **Step 2:** ADR `0023-saga-reconciliation.md` — decision: a scheduled money-safe reconciler over compensation (no SEC/orchestrator); the POSTING-state reconciliation against the accounts ledger (never blind-timeout a state where money may have moved); re-emit-idempotent re-drive for pre-money states; hard `SAGA_TIMEOUT` only when money is confirmed not moved; ShedLock for single-runner; transport-error → skip (never fail money on a failed query). Consequences (a transfer can sit until `fail-after`; a permanently-down accounts blocks completion but never corrupts money), alternatives (orchestrated saga; compensation/refund; outbox-only with infinite retry).
- [ ] **Step 3:** `gradle build` → BUILD SUCCESSFUL.
- [ ] **Step 4: commit**
```bash
git add examples/acme-bank/README.md docs/decisions/0023-saga-reconciliation.md
git commit -m "docs: reconciler README + ADR 0023 money-safe saga reconciliation"
```

---

## Done criteria for BANK-12

- A stuck transfer is detected and driven to a terminal state by the scheduled reconciler, **money-safely**: POSTING is reconciled against accounts' ledger (complete if posted, re-drive or timeout only when confirmed not posted); pre-money states re-drive then `SAGA_TIMEOUT`; a transport error never fails a transfer.
- ShedLock ensures a single runner across replicas.
- `/internal/postings/{id}` exposes the money truth; network-segmented.
- `EventuallyConsistent` marker + `acme.saga.dlt` metric added.
- ADR 0023 + README; `gradle build` green.

---

## Self-review notes

- **Spec coverage:** stuck-saga reconciler (T2,T3) ✓; money-safe POSTING reconciliation via the ledger truth (T1,T3) ✓; EventuallyConsistent marker (T4) ✓; DLT alerting (T4) ✓.
- **Type consistency:** `AccountsPostingClient.posted(id): Optional<Boolean>`; `Transfer.timeOut()` (pre-money only) vs `complete()` (POSTING→COMPLETED) vs `fail(reason)`; `findStuck(...)`; `reconcileOne(Transfer)` called by both the schedule and the IT; events re-published are the existing `TransferRequestedEvent`/`PostingRequestedEvent`/`TransferCompletedEvent`/`TransferFailedEvent` (BANK-5) so the existing externalization routes them.
- **No placeholders:** thresholds, endpoint, tiered policy concrete.
- **Money-safety invariant (the crux):** the reconciler NEVER marks a transfer FAILED while money may have moved — POSTING is failed only after accounts confirms `posted=false`; a transport error (empty) skips the round. Re-emitting is idempotent (inbox dedup + posting PK). This preserves the BANK-11 strong-consistency guarantee end-to-end.
- **Risk:** re-publishing a domain event via the Modulith outbox creates a new `event_publication`/Kafka message — confirm re-publish actually re-sends (it does: each `publishEvent` → new publication). ShedLock must be wired over the transfers datasource (reuse the repo's existing ShedLock pattern; if none, add the Jdbc `LockProvider` + the `shedlock` table migration). The `/internal/**` permit must NOT widen the gateway edge (gateway proxies only `/v1/**`; accounts isn't published to host) — note in the ADR.
