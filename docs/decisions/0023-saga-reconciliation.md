# 0023 — Money-Safe Saga Reconciliation: Scheduled Self-Healing over Orchestration

**Date:** 2026-06-18
**Status:** Accepted

## Context

The money-movement saga (ADR-0017) is choreographed: every hop is
`consume → inbox-dedup → advance → emit`, with no central orchestrator. ADR-0022 made the
single money-moving hop (`PostTransferHandler` → `JpaLedger.save`) strongly consistent — one
ACID transaction with a pessimistic source-account lock, so concurrent debits can't overdraw.

That fixes correctness *when the saga runs to completion*. It does nothing for **liveness**.
Choreography has no component whose job is to notice that a transfer stopped advancing. A lost
event (a broker hiccup, a consumer that crashed between `advance` and `emit`, a message that
exhausted its retries and was routed to the DLT) leaves a transfer stuck in a non-terminal state
(`REQUESTED`/`APPROVED`/`POSTING`) **forever**. No timeout, no alert, no convergence.

The hard part is the `POSTING` state: it is the one place where money may *already have moved*
(the ledger posting may have committed while the `ledger-posted` event that should have completed
the transfer was lost). Any recovery that blindly fails a stuck `POSTING` transfer could mark a
transfer `FAILED` while its money actually moved — corrupting the end-to-end money truth that
ADR-0022 established.

## Decision

Add a scheduled, **money-safe** reconciler in `transfers` (`SagaReconciler`) that detects stuck
transfers and drives them to a terminal state without ever contradicting the ledger.

**Single runner.** `@Scheduled(fixedDelay)` + `@SchedulerLock(name="transfer-saga-reconciler",
lockAtMostFor=PT2M)` (ShedLock `JdbcTemplateLockProvider` over the transfers datasource, the
existing acme-observability pattern). Across replicas exactly one runs a given tick — no double
re-drive, no split-brain timeouts.

**The sweep.** `findStuck(nonTerminalStatuses, updated_at < nudgeCutoff, batchSize)` — a new
`updated_at` column (touched on every save) ages the saga. Each row is reconciled in its **own**
`REQUIRES_NEW` transaction so one bad row never aborts the batch and each save+publish is atomic.

**The tiered, money-safe policy** (`reconcileOne`):

- **Pre-money** (`REQUESTED`/`APPROVED`): re-emit the upstream event to re-drive
  (`transfer-requested` / `posting-requested`). Past `fail-after`, hard `timeOut()` →
  `FAILED(SAGA_TIMEOUT)`. Always safe — no ledger posting can have happened before `POSTING`.
- **`POSTING`** (the money state): ask accounts — the ledger is the source of truth — via
  `GET /internal/postings/{id}` (`AccountsPostingClient`, returns `Optional<Boolean>`):
  - `true`  → `complete()` → `COMPLETED` (recovers a lost `ledger-posted`).
  - `false` and past `fail-after` → `fail("SAGA_TIMEOUT")` (money **confirmed** not moved → safe).
  - `false` and within the nudge window → re-emit `posting-requested` (idempotent at accounts).
  - `empty` (transport error) → **skip this round**. The transfer is left untouched.

`timeOut()` is a guarded domain transition allowed only from pre-money states; it throws from
`POSTING`/terminal, so the "never blind-timeout a money state" rule is enforced in the aggregate,
not just the reconciler.

**The money-truth query is network-internal.** `/internal/**` is permitted without a bearer
(`acme.security.permit-paths` in accounts) but is **not** exposed at the gateway edge — the
gateway proxies only `/v1/**`, and compose does not publish accounts to the host (only the gateway
is published). So the endpoint is reachable only on the service network; the permit does not widen
the public edge.

**Re-drive is idempotent.** Re-publishing a domain event creates a fresh Modulith outbox
publication → a new Kafka message. This is safe because every consumer is inbox-deduped and the
posting is PK-anchored (ADR-0022), so a redelivery never double-screens or double-posts.

**DLT alerting (complementary).** The reconciler handles *silent hangs*. A *poisoned* message —
one that fails past retries and is routed to `<topic>-dlt` — increments a Micrometer counter
`acme.saga.dlt` (tagged by topic) with a WARN, at the starter-level `DeadLetterPublishingRecoverer`
(`acme-messaging`). The two together cover both failure modes (silent loss vs. poison).

## The money-safety invariant

> The reconciler **never** marks a transfer `FAILED` while money may have moved.

A `POSTING` transfer is failed **only** after accounts confirms `posted=false`. A transport error
(`Optional.empty()`) skips the round and changes no state — the absence of an answer is never
treated as "no money moved." This preserves the ADR-0022 strong-consistency guarantee end-to-end:
strong at the mutation, self-converging in the coordination, and the two never contradict.

## Alternatives considered

- **Orchestrated saga (SEC / state-machine orchestrator).** A central coordinator would own
  timeouts directly. Rejected: it abandons the choreography this example exists to demonstrate and
  centralizes the very coupling choreography avoids. A scheduled reconciler restores liveness while
  keeping the services autonomous.
- **Compensation / refund.** Reverse a posting that completed. Rejected here: there is nothing to
  compensate — a stuck `POSTING` that *did* post should `COMPLETE`, not refund; and one that did
  not post never moved money. Reconciliation against the ledger reaches the correct terminal state
  without a compensating transaction.
- **Outbox-only with infinite retry.** Retrying the publish forever assumes the event was merely
  undelivered. It cannot recover a transfer whose *state* never advanced (e.g. a consumer that
  committed the inbox row but crashed before emitting), and it never times out a genuinely dead
  saga. The reconciler reasons over transfer *state*, not just delivery.

## Consequences

- A stuck transfer is always driven to a terminal state, money-safely: `POSTING` is reconciled
  against the ledger; pre-money states re-drive then `SAGA_TIMEOUT`; a transport error never fails
  a transfer.
- **A transfer can sit non-terminal until `fail-after`** (default `PT5M`) before being hard-failed.
  This is the deliberate cost of never failing money prematurely.
- **A permanently-down accounts blocks completion of a posted-but-unconfirmed transfer** (the query
  keeps returning `empty`, so the reconciler keeps skipping) — but it **never corrupts money**: the
  transfer stays `POSTING` until accounts answers. Liveness is sacrificed for safety, by design.
- ShedLock ensures a single runner across replicas; re-drive is idempotent (inbox dedup + posting
  PK); the `/internal/**` permit does not widen the gateway edge.
- `EventuallyConsistent` (acme-cqrs) is added as the documentation sibling of `StronglyConsistent`:
  it marks a flow whose effects converge asynchronously (outbox/inbox + reconciliation, no
  surrounding transaction). `TransactionMiddleware` treats it like an unmarked command (no tx).
