# BANK-16: saga-settle scaling — partitions, concurrency, latency tuning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** Reduce saga-settle latency and raise saga throughput by partitioning all saga topics, raising per-service consumer concurrency, and tuning producer/consumer latency settings — all keyed to preserve per-entity ordering, with NO money-safety surface touched.

**Architecture:** Pure parallelism + latency config. Each saga topic gets N partitions (declared by its producer via `NewTopic`); each consuming service raises `listener.concurrency` (≤ partitions); producers/consumers get latency tuning (`linger.ms`, `fetch-max-wait`, `max-poll-records`). Keying is unchanged (transferId for most; source-account for `posting-requested` from BANK-15) so per-entity ordering and all dedup/anchor guarantees hold.

**Tech Stack:** Java 21, Spring Boot 3.5.6, Spring Kafka (`NewTopic`/`KafkaAdmin`, listener concurrency, producer/consumer props), Testcontainers.

> Spec: `docs/superpowers/specs/2026-06-18-acme-bank-scaling-design.md` §2. Builds on BANK-15.
> **Environment (every task):** work from `/Users/npden4ik/Projects/java-boilerplate`, on `master`; system `gradle` (8.14) NEVER `./gradlew`; JDK 21; Docker up (Postgres/Redpanda cached). `gradle <module>:spotlessApply` before each commit.

---

## Task 1: declare all saga topics as multi-partition (deterministic — producer AND consumer)

**Files:** extend each service's `SagaTopicsConfig.java` (transfers + accounts already have one from BANK-15/its fix; add to antifraud, notifications, gateway); a shared partition-count property; consumer `allow.auto.create.topics: false`.

> **Pattern (from the BANK-15 review):** declaring a topic's `NewTopic` only on its PRODUCER lets a consumer that starts first auto-create it at the broker default (1 partition) → a silent throughput funnel. So EVERY service declares a `NewTopic` for every saga topic it PRODUCES OR CONSUMES; its own `KafkaAdmin` provisions/grows the topic to the declared partition count at context init (before listeners subscribe), and consumers set `allow.auto.create.topics: false` so a missing topic is a loud error, never a 1-partition funnel. Concurrent identical `NewTopic` declarations across services are idempotent.

- [ ] **Step 1:** Map each saga topic to the services that touch it (producer + consumers):
  - `transfer-requested` — producer transfers; consumers antifraud, gateway.
  - `transfer-screened` — producer antifraud; consumers transfers, gateway.
  - `posting-requested` — producer transfers; consumer accounts. (done BANK-15 + fix — both declare it.)
  - `ledger-posted` / `posting-rejected` — producer accounts; consumer transfers.
  - `transfer-completed` / `transfer-failed` — producer transfers; consumers notifications, gateway.
- [ ] **Step 2:** In EACH service, declare a `@Bean NewTopic` (in that service's `SagaTopicsConfig`) for every saga topic it produces OR consumes: `TopicBuilder.name(<topic>).partitions(${acme.bank.topics.partitions:6}).replicas(1)`. Use one shared property `acme.bank.topics.partitions` (default 6); fold BANK-15's `posting-requested` partition property into it (or keep both at 6 — consistent). Set `spring.kafka.consumer.properties.allow.auto.create.topics: false` in every consuming service's `application.yaml`.
- [ ] **Step 3:** Add/extend a light IT per service asserting its declared topics have 6 partitions via admin `describeTopics` (mirror BANK-15's `PostingRequestedTopicIT`). Keep it cheap.
- [ ] **Step 4:** Run each touched module's tests → green (and the saga ITs — with `allow.auto.create.topics=false` the test topics must be provisioned by the `NewTopic` beans in the test context; verify no IT breaks on a missing topic).
- [ ] **Step 5: commit**
```bash
gradle :examples:acme-bank:transfers:spotlessApply :examples:acme-bank:antifraud:spotlessApply :examples:acme-bank:accounts:spotlessApply :examples:acme-bank:notifications:spotlessApply :examples:acme-bank:gateway:spotlessApply
git add examples/acme-bank
git commit -m "feat(bank): every service provisions the saga topics it touches at 6 partitions (deterministic; no auto-create funnel)"
```

---

## Task 2: raise per-service consumer concurrency

**Files:** `application.yaml` in each consuming service: antifraud, transfers, accounts (done BANK-15), notifications, gateway.

- [ ] **Step 1:** For each consumer, set `spring.kafka.listener.concurrency: ${<SVC>_LISTENER_CONCURRENCY:6}` (≤ the 6 partitions). Map per service:
  - antifraud (consumes `transfer-requested`).
  - transfers (consumes `transfer-screened`, `ledger-posted`, `posting-rejected`).
  - notifications (consumes `transfer-completed`, `transfer-failed`).
  - gateway (consumes all four for the projection — its 4 listeners).
  - accounts already set in BANK-15.
  If a service uses a CUSTOM listener container factory (check each — some define an Avro factory), set `.setConcurrency(...)` on that factory from the property instead of relying on the global property.
- [ ] **Step 2:** Verify existing ITs still pass with concurrency raised (they run against a Testcontainers topic; concurrency ≤ partitions, and with a 1-partition test topic concurrency simply caps at 1 — harmless). Run each module's tests.
- [ ] **Step 3: commit**
```bash
gradle :examples:acme-bank:antifraud:spotlessApply :examples:acme-bank:transfers:spotlessApply :examples:acme-bank:notifications:spotlessApply :examples:acme-bank:gateway:spotlessApply
git add examples/acme-bank
git commit -m "feat(bank): raise per-service Kafka listener concurrency to match partition count"
```

---

## Task 3: producer/consumer latency tuning

**Files:** `application.yaml` per service (or a shared default if the messaging/observability starter exposes one).

- [ ] **Step 1:** Apply latency-oriented Kafka tuning (favor low settle latency without sacrificing safety):
  - Producer: `spring.kafka.producer.properties.linger.ms: ${KAFKA_LINGER_MS:5}` (small batching window — low latency; 0–5ms), `acks: all` (KEEP — durability; do NOT lower for money), `enable.idempotence: true` (KEEP if set — exactly-once producer semantics).
  - Consumer: `spring.kafka.consumer.properties.fetch.max.wait.ms: ${KAFKA_FETCH_MAX_WAIT_MS:50}` (lower poll latency), `max.poll.records: ${KAFKA_MAX_POLL_RECORDS:50}` (bounded batch). 
  - Do NOT change `auto-offset-reset` (gateway projection needs `earliest`), `acks`, or the inbox/anchor logic.
- [ ] **Step 2:** Confirm `acks=all` + idempotent producer remain (money durability) — these tuning changes must NOT trade away delivery guarantees. State this in the commit/ADR.
- [ ] **Step 3:** Run the saga ITs (`TransferAdvanceIT`, `PostingFlowIT`, `ScreeningIT`, `NotificationIT`, gateway projection IT) → green under the new settings.
- [ ] **Step 4: commit**
```bash
gradle :examples:acme-bank:spotlessApply 2>/dev/null || true
git add examples/acme-bank
git commit -m "feat(bank): Kafka latency tuning (linger/fetch-max-wait/max-poll) for saga settle; durability (acks=all, idempotence) retained"
```

---

## Task 4: full build + ADR

- [ ] **Step 1:** `gradle build` → BUILD SUCCESSFUL (all saga ITs + money-safety tests green).
- [ ] **Step 2:** ADR `docs/decisions/0026-saga-settle-tuning.md` — decision: partition all saga topics + raise consumer concurrency + latency-tune, keyed to preserve per-entity ordering; durability (`acks=all`, idempotent producer) explicitly retained — settle latency reduced WITHOUT trading delivery/ordering/money guarantees; the partition count (6) is a starting point, tune to the workload; consumer concurrency ≤ partitions. Note the BANK-17 benchmark will quantify the delta. Alternatives (fewer partitions; lower acks for latency — rejected for money).
- [ ] **Step 3: commit**
```bash
git add docs/decisions/0026-saga-settle-tuning.md
git commit -m "docs: ADR 0026 saga-settle tuning (partitions + concurrency + latency; durability retained)"
```

---

## Done criteria for BANK-16

- All saga topics declared multi-partition (6) by their producers; each consuming service's listener concurrency raised to match; producer/consumer latency tuned.
- Durability retained (`acks=all`, idempotent producer); keying/ordering and all inbox/anchor/lock guarantees unchanged; money-safety tests green.
- `gradle build` green; ADR 0026. (Quantified delta comes in BANK-17.)

---

## Self-review notes

- **Spec coverage:** §2 partition saga topics (T1), concurrency (T2), latency tuning (T3) ✓.
- **Type consistency:** shared `acme.bank.topics.partitions` (default 6); per-service `<SVC>_LISTENER_CONCURRENCY`; `NewTopic`/`TopicBuilder` mirrors BANK-15 `SagaTopicsConfig`.
- **No placeholders:** concrete topic list, properties, values.
- **Money-safety:** NOTHING in the money path changes — keying preserves per-entity ordering, inbox dedup + posting anchor + source lock all unchanged, `acks=all` + idempotent producer retained. This is pure parallelism/latency config.
- **Risk:** concurrency > partitions just idles extra threads (harmless); a 1-partition Testcontainers topic caps test concurrency at 1 (ITs unaffected). Re-partitioning an existing keyed topic remaps keys — the example deploys fresh (`down -v`), so partitions are fixed at first create. Lowering `linger`/`fetch-max-wait` trades a little CPU/throughput for latency — acceptable for the settle goal; `acks` untouched.
