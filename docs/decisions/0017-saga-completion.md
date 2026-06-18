# 0017 — Saga Completion: Choreographed Money-Movement via Avro Events

**Date:** 2026-06-18
**Status:** Accepted

## Context

BANK-3 (ADR-0015) established the antifraud Avro consumer pattern: `@KafkaListener` + inbox dedup + domain event publication via Spring Modulith outbox. The transfers aggregate (BANK-2) had a `rehydrate` stub that always reconstructed REQUESTED state — blocking the consume-side from advancing persisted transfers.

Three gaps remained to close the saga:
1. **transfers consume-side** — consume `TransferScreened`, `LedgerPosted`, `PostingRejected` and advance the `Transfer` aggregate
2. **accounts Kafka adapter** — consume `PostingRequested`, execute double-entry posting, emit result
3. **notifications service** — consume terminal events, store + mock-deliver a notification

## Decision

### Pattern: consume → inbox-dedup → advance → emit (repeated at every hop)

Every consumer follows the BANK-3 pattern:

```
@KafkaListener  →  Inbox.firstTime(listener, transferId)
                →  load aggregate / execute use-case
                →  save / emit domain event
                →  Spring Modulith outbox externalizes to Avro
```

This gives **effectively-once** processing at each hop: the inbox dedup, state mutation, and event publication commit in a single database transaction. The Modulith outbox guarantees at-least-once delivery of the next Avro event.

### Transfer aggregate rehydration fix

`JpaTransfers.rehydrate` previously called `Transfer.request(...)` regardless of persisted status, resetting every loaded aggregate to REQUESTED. The fix adds a `Transfer.rehydrate(id, source, dest, amount, requestedBy, status, failureReason)` static factory that bypasses the REQUESTED-only `request` path, allowing the consumer listeners to call state-transition methods on aggregates already at APPROVED, POSTING, etc.

### Multi-event externalization in transfers

`TransferExternalizationConfig` now selects and maps four domain event types:
- `TransferRequested` → `transfer-requested`
- `PostingRequestedEvent` → `posting-requested`
- `TransferCompletedEvent` → `transfer-completed`
- `TransferFailedEvent` → `transfer-failed`

The `select` predicate uses `instanceof` union; per-type `.mapping()` and `.route()` are chained. This mirrors the single-event pattern (ADR-0014) extended to multiple types.

### Accounts listener and nested transactions

`PostTransferCommand` is `StronglyConsistent`, which causes `TransactionMiddleware` to call `TransactionTemplate.execute(...)`. `TransactionTemplate` defaults to `PROPAGATION_REQUIRED`, so it **joins** the listener's outer `@Transactional` transaction rather than starting a new one. The inbox insert, posting, and event publication all commit atomically in a single transaction.

### Notifications: terminal consumer, no outbox

`notifications` is a terminal consumer — it emits no further events — so it does not need an outbox or `event_publication` table. It uses only `Inbox.firstTime` for dedup and persists a `notification` row.

### Avro schema registration

All consumers wire both `spring.kafka.consumer.properties.schema.registry.url` and `spring.kafka.producer.properties.schema.registry.url` via `DynamicPropertyRegistrar` in ITs. Existing tests that do not use Kafka add `spring.kafka.listener.auto-startup=false` to suppress listener container startup without a schema registry.

## Consequences

- The choreographed saga is fully wired: `transfer-requested → transfer-screened → posting-requested → ledger-posted|posting-rejected → transfer-completed|transfer-failed → notification`
- Every consumer is effectively idempotent: inbox dedup + outbox = at-most-once side-effects with at-least-once delivery
- The posting step (accounts) is the only money-moving step; no compensation is implemented — if posting fails the transfer is marked FAILED and no money moves
- Per-hop Avro ITs on real Redpanda + Postgres prove each saga transition
- A full compose-orchestrated e2e test is documented as the smoke path but not automated (reliability concerns with container orchestration timing)
- `Transfer.rehydrate` is the blessed path for loading non-REQUESTED aggregates; `Transfer.request` guards the REQUESTED-only initial state
