# BANK-15: per-account write scaling (single-writer, lock retained) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** Raise per-account write throughput by making each source account a single writer — route `posting-requested` keyed by source `accountId` across a multi-partition topic so all of one account's postings land on one partition/consumer — while KEEPING the BANK-11 pessimistic lock and the BANK-1 posting-PK anchor as correctness backstops. No money-safety guarantee is weakened.

**Architecture:** Defense-in-depth. The partition gives a single writer per account in steady state (no lock contention → higher throughput); the lock + anchor + inbox dedup remain to guarantee correctness during consumer rebalances and redelivery. The derived `SUM(entries)` balance stays the source of truth (no materialization).

**Tech Stack:** Java 21, Spring Boot 3.5.6, Spring Modulith event externalization, Spring Kafka (`NewTopic`/`KafkaAdmin`, listener concurrency), Testcontainers (Postgres + Redpanda).

> Spec: `docs/superpowers/specs/2026-06-18-acme-bank-scaling-design.md` §1. Builds on BANK-0..14.
> **Environment (every task):** work from `/Users/npden4ik/Projects/java-boilerplate`, on `master`; system `gradle` (8.14) NEVER `./gradlew`; JDK 21; Docker up (Postgres/Redpanda cached). `gradle <module>:spotlessApply` before each commit.

---

## Task 1: re-key `posting-requested` by source account (TDD)

**Files:** `transfers/.../adapter/out/messaging/TransferExternalizationConfig.java`, the `PostingRequestedEvent` (confirm it carries `sourceAccountId`), `transfers/.../TransferExternalizationIT.java` (or a new `PostingRequestedKeyIT`).

- [ ] **Step 1:** Read `TransferExternalizationConfig` — find the `.route(PostingRequestedEvent.class, e -> RoutingTarget.forTarget("posting-requested").andKey(e.transferId()))` (or equivalent). Confirm `PostingRequestedEvent` exposes `sourceAccountId()` (it maps to the Avro `PostingRequested` which carries source/destination/amount — it does; verify).
- [ ] **Step 2: failing test** — extend `TransferExternalizationIT` (or new `PostingRequestedKeyIT`, Postgres+Redpanda): initiate/advance a transfer to the point it emits `posting-requested`, consume the raw record from the `posting-requested` topic, assert `record.key()` equals the **source accountId** (NOT the transferId). (Today it equals transferId → RED.)
- [ ] **Step 3: run, FAIL.**
- [ ] **Step 4:** Change the route key to the source account: `.andKey(e.sourceAccountId())` for `PostingRequestedEvent`. Leave the other events' keys as-is (`transferId`). 
- [ ] **Step 5: run, PASS.** Also run the full transfers suite — the saga ITs (`TransferAdvanceIT`, etc.) must stay green (the key change doesn't affect single-partition test topics' delivery).
- [ ] **Step 6: commit**
```bash
gradle :examples:acme-bank:transfers:spotlessApply
git add examples/acme-bank/transfers
git commit -m "feat(transfers): key posting-requested by source account (single-writer-per-account routing)"
```

---

## Task 2: declare `posting-requested` as a multi-partition topic

**Files:** a Kafka topic config in the producer (transfers) — `transfers/.../config/SagaTopicsConfig.java` (new) — declaring `posting-requested` with N partitions; `application.yaml` (partition count property).

- [ ] **Step 1:** Add `SagaTopicsConfig` with a `@Bean NewTopic postingRequestedTopic()` using `TopicBuilder.name("posting-requested").partitions(${acme.bank.topics.posting-requested.partitions:6}).build()`. `KafkaAdmin` (auto-configured) creates/updates it on startup. (Keep replicas=1 for the single-broker dev/Redpanda; document that production sets replication.) Add the partition-count property to `application.yaml` with a default of 6.
- [ ] **Step 2:** Note in a comment: increasing partitions on an EXISTING keyed topic remaps keys; the example deploys fresh (`compose down -v`), so the partition count is fixed at first create — clean. (No test asserts partition count against Redpanda unless trivial; if easy, add an admin `describeTopics` assertion that `posting-requested` has ≥2 partitions in an IT.)
- [ ] **Step 3:** `gradle :examples:acme-bank:transfers:test` → green.
- [ ] **Step 4: commit**
```bash
gradle :examples:acme-bank:transfers:spotlessApply
git add examples/acme-bank/transfers
git commit -m "feat(transfers): declare posting-requested as a multi-partition topic (spread accounts across partitions)"
```

---

## Task 3: accounts consumer concurrency + keep lock/anchor (verify safety holds)

**Files:** `accounts/src/main/resources/application.yaml` (listener concurrency), the accounts Avro listener container factory if concurrency must be set there, `accounts/.../PostTransferHandler.java` (UNCHANGED — confirm lock + anchor stay), `ConcurrentDebitIT` (BANK-11 — must stay green).

- [ ] **Step 1:** Raise the accounts `posting-requested` consumer concurrency so multiple partitions are consumed in parallel (one thread per partition). If accounts uses the default container factory, set `spring.kafka.listener.concurrency: ${ACCOUNTS_LISTENER_CONCURRENCY:6}` in `application.yaml`. If it uses a CUSTOM Avro `ConcurrentKafkaListenerContainerFactory` (check — antifraud/accounts may define one), set `.setConcurrency(...)` on that factory from the property. Concurrency ≤ partition count (6). Within a partition it's still single-threaded → single-writer per account preserved.
- [ ] **Step 2: CONFIRM the safety backstops are untouched** — `PostTransferHandler` still does `accounts.findByIdForUpdate(sourceId)` (the lock) and `ledger.save` still inserts the posting-PK anchor; `PostingRequestedListener` still `inbox.firstTime("accounts", transferId)`. Do NOT remove any of these.
- [ ] **Step 3: money-safety regression** — run the BANK-11 `ConcurrentDebitIT` (8 threads, distinct transferIds, one source, debit-to-overdraft): it MUST stay green (proves the lock still prevents overdraft when two writers hit one account — the rebalance edge). Run `gradle :examples:acme-bank:accounts:test --tests "*ConcurrentDebitIT"`.
- [ ] **Step 4:** Add `accountsListenerConcurrencyIsParallel` light IT (optional, if cheap): produce `posting-requested` for TWO different source accounts and assert both post (they're on different partitions → parallel). Skip if flaky; the concurrency property + the green ConcurrentDebitIT are the core deliverable.
- [ ] **Step 5:** `gradle :examples:acme-bank:accounts:test` → green (PostTransferIT, PostingFlowIT, OpenAccountIT, ConcurrentDebitIT all pass).
- [ ] **Step 6: commit**
```bash
gradle :examples:acme-bank:accounts:spotlessApply
git add examples/acme-bank/accounts
git commit -m "feat(accounts): parallel posting-requested consumption (per-partition concurrency); lock + anchor retained as backstops"
```

---

## Task 4: rebalance-proxy safety test + reconciler consistency

**Files:** `accounts/.../ConcurrentPostingConsumerIT.java` (new — two consumers, same account), confirm BANK-12 reconciler re-emits keyed by source account.

- [ ] **Step 1:** `ConcurrentPostingConsumerIT` (Postgres+Redpanda) — the rebalance-edge proxy: produce N `posting-requested` Avro records for the SAME source account but DISTINCT transferIds (e.g. one funded with enough for only some), with the accounts listener running at concurrency ≥2 against a `posting-requested` topic of ≥2 partitions. Because they share a source-account KEY they land on ONE partition → one consumer thread (single-writer) → assert: source never overdraws, the funded subset posts, the rest are `posting-rejected` (INSUFFICIENT_FUNDS), ledger Σ=0. This asserts the single-writer property AND that even if two threads contended (rebalance) the lock would hold. (If forcing true two-thread contention on one key is impractical via Kafka, rely on `ConcurrentDebitIT` for the lock proof and make this IT assert the single-writer ordering / no-overdraft outcome.)
- [ ] **Step 2:** Confirm the BANK-12 `SagaReconciler` re-emit of `PostingRequestedEvent` now flows through the same `.andKey(sourceAccountId)` route (it publishes the same event type → same externalization route) → re-drives land on the same account partition → consistent single-writer. No code change expected; verify by reading the route + reconciler. Add a one-line note/test if cheap.
- [ ] **Step 3: run, PASS.**
- [ ] **Step 4: commit**
```bash
gradle :examples:acme-bank:accounts:spotlessApply
git add examples/acme-bank/accounts
git commit -m "test(accounts): single-writer-per-account safety under concurrent same-account postings (rebalance-proxy)"
```

---

## Task 5: full build + ADR

- [ ] **Step 1:** `gradle build` → BUILD SUCCESSFUL (all money-safety tests green: ConcurrentDebitIT, PostTransferIT, PostingFlowIT, the saga ITs, reconciler ITs).
- [ ] **Step 2:** ADR `docs/decisions/0025-per-account-write-scaling.md` — decision: single-writer-per-account via source-keyed multi-partition `posting-requested`, with the pessimistic lock + posting-PK anchor RETAINED as backstops (defense-in-depth); why the lock stays (uncontended in steady state, correctness at the rebalance edge); the derived balance stays source of truth (no materialization); the destination-credit commutativity argument; the honest single-host benchmark caveat (the ceiling is raised for multi-host hot accounts, not the local Postgres-CPU-bound limit). Alternatives rejected (write-side balance + conditional UPDATE — divergence risk + materialization; removing the lock — loses the rebalance-edge guarantee).
- [ ] **Step 3: commit**
```bash
git add docs/decisions/0025-per-account-write-scaling.md
git commit -m "docs: ADR 0025 per-account write scaling (single-writer + retained lock backstop)"
```

---

## Done criteria for BANK-15

- `posting-requested` is keyed by source account and multi-partition; a source account's postings serialize on one partition/consumer (single writer) → no lock contention in steady state.
- The BANK-11 lock + BANK-1 posting-PK anchor + inbox dedup are ALL retained; the `ConcurrentDebitIT` overdraft test stays green; a same-account concurrent-posting safety test passes.
- The BANK-12 reconciler re-emits consistently keyed by source account.
- No money-safety guarantee weakened; derived balance unchanged; `gradle build` green; ADR 0025.

---

## Self-review notes

- **Spec coverage:** §1 source-keyed routing (T1), multi-partition (T2), concurrency + retained guards (T3), rebalance-proxy safety + reconciler consistency (T4) ✓.
- **Type consistency:** `PostingRequestedEvent.sourceAccountId()`; `.andKey(e.sourceAccountId())`; `NewTopic` partitions property `acme.bank.topics.posting-requested.partitions`; accounts `spring.kafka.listener.concurrency` / factory `.setConcurrency`. `PostTransferHandler.findByIdForUpdate` + posting-PK anchor UNCHANGED.
- **No placeholders:** concrete route key, topic bean, concurrency property, tests.
- **Money-safety gate:** the BANK-11 `ConcurrentDebitIT` is the regression guard — it MUST stay green (the lock is retained and still serializes concurrent writers on one account). Σ=0 / idempotency / asset / operational invariants untouched. Derived balance unchanged (no materialization).
- **Risk:** if `posting-requested` had 1 partition, keying by account would funnel ALL accounts to one consumer thread (a regression) — Task 2 makes it multi-partition so accounts spread. Partition count vs accounts: many accounts hash across the 6 partitions; a single hot account still maps to one partition (its single-writer lane) — that's the intended per-account serialization, not a bug. Reconciler re-emit stays consistent (same event → same route key).
