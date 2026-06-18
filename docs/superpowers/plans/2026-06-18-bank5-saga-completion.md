# BANK-5: saga completion (consume-side + accounts adapter + notifications) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Close the money-movement saga. Wire the Avro consume sides: `transfers` advances on `TransferScreened`/`LedgerPosted`/`PostingRejected` (and fix the BANK-2 rehydration stub so persisted transfers reload their real state); `accounts` gains a Kafka adapter that consumes `PostingRequested`, posts the double-entry, and emits `LedgerPosted`/`PostingRejected`; a new `notifications` service consumes the terminal events and stores a (mock-delivered) notification. Prove each hop with Avro ITs on Redpanda.

**Architecture:** Every consumer follows the BANK-3 pattern (Avro `@KafkaListener` + inbox dedup + `@Transactional` + emit a domain event externalized to Avro per BANK-2). `transfers` adds `adapter/in/messaging` listeners that load the `Transfer` aggregate, call a state transition, persist, and publish the next domain event; the `JpaTransfers.rehydrate` is fixed to reconstruct the persisted `status`/`failureReason`. `accounts` adds `adapter/in/messaging` (`PostingRequestedListener`) calling the existing `PostTransfer` command and an outbox emitting `LedgerPosted`/`PostingRejected`. `notifications` is a new minimal service. Domains stay framework/Avro-free (ArchUnit).

**Tech Stack:** Java 21, Spring Boot 3.5.6, acme-* starters, bank-contracts (Avro), Spring Kafka (KafkaAvroDeserializer + Modulith Avro externalization), Testcontainers Redpanda + Postgres, Awaitility, ArchUnit.

> Spec: `docs/superpowers/specs/2026-06-18-acme-bank-design.md` §7. Builds on BANK-0..4. Topic convention: event-name topics (`transfer-requested`, `transfer-screened`, `posting-requested`, `ledger-posted`, `posting-rejected`, `transfer-completed`, `transfer-failed`).
> **Reference patterns (read in-repo, mirror):** `examples/acme-bank/antifraud` (Avro consume `@KafkaListener` + `Inbox.firstTime` + emit via Modulith Avro externalization + `MoneyJacksonConfig` + `enable-json:false` + Avro consumer/producer config + the `ScreeningIT`/redelivery IT shape), `examples/acme-bank/transfers` (`TransferExternalizationConfig`, `Transfer` aggregate, `JpaTransfers`), `examples/acme-bank/accounts` (`PostTransferCommand`/`Pipeline`, JpaLedger).
> **Environment (every task):** work from `/Users/npden4ik/Projects/java-boilerplate`; system `gradle` (8.14) NEVER `./gradlew`; JDK 21 toolchain; Docker up, Redpanda + Postgres cached; Confluent slow. `gradle <module>:spotlessApply` before each commit. Exclude generated Avro from Spotless.

---

## Task 1: transfers — fix rehydration + consume-side listeners

**Files:** `JpaTransfers.java` (fix rehydrate), `Transfer.java` (add a `rehydrate` factory), domain events (`TransferApproved`?), `adapter/in/messaging/{ScreeningResultListener, PostingResultListener}.java`, externalization additions, test.

- [ ] **Step 1: rehydrate fix (TDD)** — add a `Transfer.rehydrate(...)` static factory that reconstructs any state from persisted fields, and a unit test in `TransferTest` asserting a rehydrated `COMPLETED`/`FAILED` transfer reports that status. Implementation: a package-private/static `rehydrate(TransferId, source, dest, amount, requestedBy, TransferStatus status, String failureReason)` that builds the aggregate with the given status (bypassing the REQUESTED-only `request` path). Update `JpaTransfers.rehydrate` to call it with `TransferStatus.valueOf(e.getStatus())` + `e.getFailureReason()`.
- [ ] **Step 2: emit-on-advance events** — the saga advances by consuming a result and publishing the next domain event, which Modulith externalizes to Avro. Add domain events: `PostingRequestedEvent(transferId, sourceAccountId, destinationAccountId, Money amount)`, `TransferCompletedEvent(transferId)`, `TransferFailedEvent(transferId, reason)` (clean `@DomainEvent` records, no Avro). Add the Avro mappers (`TransferAvroMapper` additions or new mappers) + extend `TransferExternalizationConfig` to select/map/route each: `PostingRequestedEvent → Avro PostingRequested → topic posting-requested`, `TransferCompletedEvent → Avro TransferCompleted → transfer-completed`, `TransferFailedEvent → Avro TransferFailed → transfer-failed`. (Mirror the existing TransferRequested mapping; the config's `select` predicate must match ALL these event types — use a package-based or multi-type selector.)
- [ ] **Step 3: ScreeningResultListener** — `@KafkaListener(topics="transfer-screened", groupId="transfers")` receiving Avro `TransferScreened`, `@Transactional`, `Inbox.firstTime("transfers-screening", transferId)`; on first time: load the `Transfer` (`transfers.findById`), if `approved` → `transfer.approve(); transfer.markPosting(); transfers.save(transfer); publishEvent(new PostingRequestedEvent(...))`; else → `transfer.reject(reason); transfers.save(transfer); publishEvent(new TransferFailedEvent(transferId, reason))`.
- [ ] **Step 4: PostingResultListener** — two listeners (or one per topic): `@KafkaListener(topics="ledger-posted", groupId="transfers")` (Avro `LedgerPosted`) → load transfer, `transfer.complete(); save; publishEvent(TransferCompletedEvent)`; `@KafkaListener(topics="posting-rejected", groupId="transfers")` (Avro `PostingRejected`) → `transfer.fail(reason); save; publishEvent(TransferFailedEvent)`. Each inbox-deduped.
- [ ] **Step 5: config** — transfers `application.yaml`: add the Avro consumer (`spring.kafka.consumer.value-deserializer=KafkaAvroDeserializer`, `specific.avro.reader=true`, SR url). It already has the Avro producer + `enable-json:false` + `MoneyJacksonConfig`. Add the `processed_messages` table to transfers' Flyway (V2 migration, copy from antifraud).
- [ ] **Step 6: compile + unit/ArchUnit green** — `gradle :examples:acme-bank:transfers:compileJava` and `:test` (existing + rehydrate unit) → BUILD SUCCESSFUL.
- [ ] **Step 7: saga-hop IT** — `TransferAdvanceIT` (Redpanda + Postgres): seed a `Transfer(REQUESTED)` (insert via JdbcTemplate or initiate), produce an Avro `TransferScreened(approved=true)` to `transfer-screened`, await a `PostingRequested` Avro on `posting-requested` (mirror `ScreeningIT`); a second case: `TransferScreened(approved=false)` → `TransferFailed` on `transfer-failed` + transfer status FAILED. Also feed `LedgerPosted` → `TransferCompleted` emitted + status COMPLETED.
- [ ] **Step 8: commit**
```bash
gradle :examples:acme-bank:transfers:spotlessApply
git add examples/acme-bank/transfers
git commit -m "feat(transfers): consume-side saga (screened/posted/rejected -> advance + emit), rehydrate fix"
```

---

## Task 2: accounts — Kafka adapter (consume PostingRequested, emit LedgerPosted/PostingRejected)

**Files:** accounts `build.gradle.kts` (add outbox+messaging+contracts), `adapter/in/messaging/PostingRequestedListener.java`, domain events (`LedgerPostedEvent`, `PostingRejectedEvent`), externalization config, `MoneyJacksonConfig`, Flyway V3 (processed_messages + event_publication), config.

- [ ] **Step 1: deps** — accounts `build.gradle.kts` add `acme-outbox`, `acme-messaging`, `bank-contracts`, awaitility (test).
- [ ] **Step 2: domain events** — `LedgerPostedEvent(transferId)`, `PostingRejectedEvent(transferId, reason)` (clean `@DomainEvent`).
- [ ] **Step 3: listener** — `adapter/in/messaging/PostingRequestedListener.java`: `@KafkaListener(topics="posting-requested", groupId="accounts")` receiving Avro `PostingRequested`, `@Transactional`, `Inbox.firstTime("accounts", transferId)`; on first time: `PostTransferResult r = pipeline.send(new PostTransferCommand(transferId, source, dest, MoneyMapper.fromAvro(amount)))`; if `r.posted()` → `publishEvent(new LedgerPostedEvent(transferId))`; else → `publishEvent(new PostingRejectedEvent(transferId, r.reason()))`.
  > Note: the `PostTransfer` command is itself `StronglyConsistent` (its own tx) AND the listener is `@Transactional`; nested — the posting is atomic and the inbox+publish commit with it. Confirm no nested-tx surprise (PROPAGATION default REQUIRED joins the outer tx). If the cqrs TransactionTemplate starts a NEW tx, adjust to avoid double-tx; mirror how antifraud composes inbox + a domain call.
- [ ] **Step 4: externalization + Jackson + Flyway + config** — mirror BANK-2/antifraud: `AccountsExternalizationConfig` mapping `LedgerPostedEvent`→Avro `LedgerPosted`→`ledger-posted` and `PostingRejectedEvent`→Avro `PostingRejected`→`posting-rejected`; `MoneyJacksonConfig`; Flyway V3 adds `processed_messages` + `event_publication`; `application.yaml` gains Avro consumer + producer + `enable-json:false`.
- [ ] **Step 5: compile + ArchUnit** — green.
- [ ] **Step 6: accounts saga-hop IT** — `PostingFlowIT` (Redpanda + Postgres): seed two OPEN accounts + a funded source (JdbcTemplate, like `PostTransferIT`), produce an Avro `PostingRequested` to `posting-requested`, await a `LedgerPosted` Avro on `ledger-posted` + assert the ledger is balanced (Σ=0) for the transfer; a second case: insufficient funds → `PostingRejected(INSUFFICIENT_FUNDS)` on `posting-rejected` + no entries. Redelivery case: same PostingRequested twice → posted once (inbox).
- [ ] **Step 7: commit**
```bash
gradle :examples:acme-bank:accounts:spotlessApply
git add examples/acme-bank/accounts
git commit -m "feat(accounts): Kafka adapter — consume PostingRequested (Avro inbox) -> post -> emit LedgerPosted/PostingRejected"
```

---

## Task 3: notifications service (consume terminal events, mock delivery)

**Files:** settings (modify), `notifications` module (hexagonal): domain (`Notification`), application (`NotifyOnTransfer`), adapter/in/messaging (listeners on `transfer-completed` + `transfer-failed`), adapter/out (JPA notification log + a mocked `DeliveryPort`), Flyway, config, IT.

- [ ] **Step 1:** Add `"examples:acme-bank:notifications",` to settings; scaffold the hexagonal tree (mirror antifraud).
- [ ] **Step 2: domain** — `Notification` (transferId, channel, message, status), `DeliveryPort` (out-port: `deliver(Notification)`), a clean `@DomainEvent` not required (terminal consumer).
- [ ] **Step 3: application** — `NotifyOnTransfer`: build a notification message from the terminal event, persist it, call `DeliveryPort.deliver` (the mock).
- [ ] **Step 4: adapters** — `adapter/in/messaging`: `@KafkaListener(topics={"transfer-completed","transfer-failed"}, groupId="notifications")` receiving the Avro `TransferCompleted`/`TransferFailed` (two listeners or a typed pair), inbox-deduped, calling `NotifyOnTransfer`. `adapter/out`: `JpaNotificationStore` + a `LoggingDeliveryAdapter implements DeliveryPort` (the mock — logs/stores, no real send). Flyway: `notification` table + `processed_messages`. No outbox needed (terminal — emits nothing).
- [ ] **Step 5: config + compile + ArchUnit** — Avro consumer config; green.
- [ ] **Step 6: notifications IT** — `NotificationIT` (Redpanda + Postgres): produce an Avro `TransferCompleted` to `transfer-completed`, await a `notification` row for the transfer (via JdbcTemplate); redelivery → one row (inbox). A second case for `TransferFailed`.
- [ ] **Step 7: commit**
```bash
gradle :examples:acme-bank:notifications:spotlessApply
git add settings.gradle.kts examples/acme-bank/notifications
git commit -m "feat(notifications): consume terminal transfer events (Avro inbox) -> store + mock delivery"
```

---

## Task 4: contracts compat + bank README + full build + ADR

- [ ] **Step 1: bank README** — `examples/acme-bank/README.md`: the choreography diagram (REST → transfers → antifraud screening → accounts posting → notifications), the topic list, the per-service hexagonal structure, how to run the full stack (`docker compose -f compose.bank.yaml up -d` + start each service), and that the automated proof is the per-hop Avro ITs (a manual end-to-end via compose is documented as the smoke path).
- [ ] **Step 2:** `gradle build` → BUILD SUCCESSFUL (all bank services + their saga-hop ITs + no regression).
- [ ] **Step 3: ADR** — `docs/decisions/0017-saga-completion.md`: choreographed saga with a coordinating Transfer aggregate; each hop is consume-Avro→inbox-dedup→advance/post→emit-Avro; effectively-once at every consumer; posting is the only money-moving step (reserve-then-post, no compensation); the per-hop ITs prove the saga; full e2e via compose is the documented smoke path.
- [ ] **Step 4: commit**
```bash
git add examples/acme-bank/README.md docs/decisions/0017-saga-completion.md
git commit -m "docs: acme-bank README + ADR 0017 saga completion"
```

---

## Done criteria for BANK-5

- `gradle build` green; all bank services' saga-hop ITs pass on Redpanda + Postgres.
- The saga is wired end-to-end across topics: `transfer-requested → transfer-screened → posting-requested → ledger-posted|posting-rejected → transfer-completed|transfer-failed`, with notifications consuming the terminal events.
- transfers rehydration reconstructs real persisted state; every consumer is idempotent (inbox dedup); domains framework/Avro-free.
- `examples/acme-bank/README.md` documents the choreography + how to run it.

---

## Self-review notes

- **Spec coverage (§7):** transfers saga state machine fully driven by events ✓, accounts posting hop ✓, antifraud (BANK-3) ✓, notifications terminal consumer ✓, effectively-once at each consumer ✓ (inbox), reserve-then-post no-compensation ✓, rehydrate fix ✓ (the BANK-2 deferred minor). Full compose e2e = documented smoke path; automated coverage = per-hop Avro ITs (more reliable in CI).
- **Type consistency:** topic names per the event-name convention; domain events (clean) vs Avro contracts (FQN-mapped); `Transfer` transitions (`approve`/`markPosting`/`complete`/`reject`/`fail`); `PostTransferCommand`/`Pipeline`; `Inbox.firstTime`; `MoneyMapper.fromAvro`.
- **Risk:** nested transactions (listener `@Transactional` + `PostTransfer` `StronglyConsistent`) — Task 2 Step 3 calls it out; default REQUIRED propagation joins the outer tx. The multi-listener externalization selector must match all the new domain event types (Task 1 Step 2). The per-hop ITs are the contracts.
- **Deferred:** the unused `TransferStatus.REJECTED` (BANK-2 minor) — `reject()` uses `FAILED`; harmless, can drop later. A real compose-orchestrated e2e test is documented but not automated (reliability).
