# BANK-3: antifraud service (Avro consumer + RiskEngine) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Build the `antifraud` service: consume the Avro `TransferRequested` event (idempotently, inbox dedup), run a deterministic `RiskEngine` (amount-limit + velocity rules, a strict rule set behind a feature flag), and emit an Avro `TransferScreened` (approved/rejected + reason) via the outbox. This closes the first saga hop and exercises the Avro **consumer** path + inbox + feature flags.

**Architecture:** Hexagonal `antifraud`, following the established BANK-1 (hexagonal/persistence) and BANK-2 (Avro outbox externalization) patterns. `domain`: `RiskDecision` (value object), `RiskEngine` (domain service, pure), `TransferScreened` clean domain event. `application`: `ScreenTransfer` use case. `adapter/in/messaging`: a `@KafkaListener` consuming the Avro `TransferRequested` from topic `transfer-requested`, deduping via the `acme-messaging` `Inbox`, calling `ScreenTransfer`, persisting the decision, and publishing the domain `TransferScreened` (Modulith → Avro externalization to topic `transfer-screened`, exactly like BANK-2). `adapter/out`: JPA decision/velocity persistence; feature-flag (`acme-featureflags`) toggles the strict rule set. Domain stays framework/Avro-free (ArchUnit).

**Tech Stack:** Java 21, Spring Boot 3.5.6, acme-money/persistence/cqrs/outbox/messaging/featureflags starters, bank-contracts (Avro), Spring Kafka (KafkaAvroDeserializer consumer), Testcontainers Redpanda + Postgres, ArchUnit, jMolecules.

> Spec: `docs/superpowers/specs/2026-06-18-acme-bank-design.md` §7/§9. Builds on BANK-0..2.
> **Reference patterns (read these in-repo, mirror their structure):** `examples/acme-bank/accounts` (hexagonal layout, JPA `MoneyAmount`, ArchUnit, Flyway, idempotency anchor), `examples/acme-bank/transfers` (Avro outbox externalization: `TransferExternalizationConfig`, `MoneyJacksonConfig`, `spring.modulith.events.kafka.enable-json: false`, `KafkaAvroSerializer` producer; the `TransferExternalizationIT` Redpanda+SR test shape). `starters/acme-messaging` provides the `Inbox` + `processed_messages` table.
> **Topic convention:** event-name topics. BANK-2 routed `TransferRequested` to topic `transfers` — **Task 1 renames it to `transfer-requested`** for consistency. Antifraud consumes `transfer-requested`, emits to `transfer-screened`.
> **Environment (every task):** work from `/Users/npden4ik/Projects/java-boilerplate`; system `gradle` (8.14) NEVER `./gradlew`; JDK 21 toolchain; Docker up, Redpanda + Postgres cached; Confluent repo reachable (slow). `gradle <module>:spotlessApply` before each commit. Exclude generated Avro from Spotless.

---

## Task 1: Normalize the TransferRequested topic name to `transfer-requested`

**Files:** Modify `examples/acme-bank/transfers/src/main/java/com/acme/bank/transfers/adapter/out/messaging/TransferExternalizationConfig.java` and `examples/acme-bank/transfers/src/test/java/com/acme/bank/transfers/TransferExternalizationIT.java`.

- [ ] **Step 1:** In `TransferExternalizationConfig`, change `RoutingTarget.forTarget("transfers")` → `RoutingTarget.forTarget("transfer-requested")`.
- [ ] **Step 2:** In `TransferExternalizationIT`, change `consumer.subscribe(List.of("transfers"))` → `consumer.subscribe(List.of("transfer-requested"))`.
- [ ] **Step 3:** Verify — `gradle :examples:acme-bank:transfers:test --tests "*TransferExternalizationIT"` → PASS (still externalizes, now to `transfer-requested`).
- [ ] **Step 4:** Commit:
```bash
git add examples/acme-bank/transfers
git commit -m "refactor(transfers): route TransferRequested to topic transfer-requested (event-name convention)"
```

---

## Task 2: antifraud module + RiskEngine domain (TDD)

**Files:** settings (modify), `antifraud/build.gradle.kts`, domain (`RiskDecision`, `RiskRules`, `RiskEngine`, `TransferScreened`), test `RiskEngineTest.java`.

- [ ] **Step 1: settings** — add `"examples:acme-bank:antifraud",` to `include(...)`.
- [ ] **Step 2: dirs** — create the standard hexagonal tree under `examples/acme-bank/antifraud/src/main/java/com/acme/bank/antifraud/{domain,application,adapter/in/messaging,adapter/out/persistence,config}` + `src/main/resources/db/migration/{postgresql,oracle}` + `src/test/java/com/acme/bank/antifraud/{domain,...}` (mirror `accounts`).
- [ ] **Step 3: build script** — `examples/acme-bank/antifraud/build.gradle.kts` (mirror `transfers` deps + add featureflags + messaging):
```kotlin
plugins {
    id("acme.bank-service-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(project(":platform:acme-bom")))
    implementation(project(":starters:acme-money"))
    implementation(project(":starters:acme-persistence-spring-boot-starter"))
    implementation(project(":starters:acme-outbox-spring-boot-starter"))
    implementation(project(":starters:acme-messaging-spring-boot-starter"))
    implementation(project(":starters:acme-featureflags-spring-boot-starter"))
    implementation(project(":examples:acme-bank:bank-contracts"))
    testImplementation(project(":starters:acme-test-support"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.awaitility)
}

spotless {
    java {
        targetExclude("build/generated-main-avro-java/**")
    }
}
```
- [ ] **Step 4: failing test** — `examples/acme-bank/antifraud/src/test/java/com/acme/bank/antifraud/domain/RiskEngineTest.java`:
```java
package com.acme.bank.antifraud.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.money.Assets;
import com.acme.money.Money;
import org.junit.jupiter.api.Test;

class RiskEngineTest {

    // standard limit 10,000 USD; strict limit 1,000 USD
    private final RiskEngine standard = new RiskEngine(new RiskRules(Money.of("10000", Assets.USD), 5));
    private final RiskEngine strict = new RiskEngine(new RiskRules(Money.of("1000", Assets.USD), 5));

    @Test
    void approvesAmountUnderLimitAndUnderVelocity() {
        RiskDecision d = standard.assess(Money.of("500.00", Assets.USD), 0);
        assertThat(d.approved()).isTrue();
    }

    @Test
    void rejectsAmountOverLimit() {
        RiskDecision d = standard.assess(Money.of("25000.00", Assets.USD), 0);
        assertThat(d.approved()).isFalse();
        assertThat(d.reason()).isEqualTo("AMOUNT_LIMIT");
    }

    @Test
    void rejectsOverVelocity() {
        RiskDecision d = standard.assess(Money.of("100.00", Assets.USD), 5);
        assertThat(d.approved()).isFalse();
        assertThat(d.reason()).isEqualTo("VELOCITY");
    }

    @Test
    void strictRulesRejectLowerAmounts() {
        assertThat(strict.assess(Money.of("2000.00", Assets.USD), 0).approved()).isFalse();
        assertThat(standard.assess(Money.of("2000.00", Assets.USD), 0).approved()).isTrue();
    }
}
```
- [ ] **Step 5: run, FAIL** — `gradle :examples:acme-bank:antifraud:test --tests "*RiskEngineTest"` → FAIL.
- [ ] **Step 6: domain** — create:

`.../domain/RiskDecision.java`:
```java
package com.acme.bank.antifraud.domain;

import org.jmolecules.ddd.annotation.ValueObject;

@ValueObject
public record RiskDecision(boolean approved, String reason) {
    public static RiskDecision approve() {
        return new RiskDecision(true, null);
    }

    public static RiskDecision reject(String reason) {
        return new RiskDecision(false, reason);
    }
}
```
`.../domain/RiskRules.java`:
```java
package com.acme.bank.antifraud.domain;

import com.acme.money.Money;
import org.jmolecules.ddd.annotation.ValueObject;

/** Tunable risk thresholds: max single-transfer amount and max prior transfers per source (velocity). */
@ValueObject
public record RiskRules(Money maxAmount, int maxVelocity) {}
```
`.../domain/RiskEngine.java`:
```java
package com.acme.bank.antifraud.domain;

import com.acme.money.Money;

/** Deterministic risk assessment: amount-limit then velocity. Pure domain — a real ML engine would
 * implement the same conceptual contract behind this type. */
public class RiskEngine {

    private final RiskRules rules;

    public RiskEngine(RiskRules rules) {
        this.rules = rules;
    }

    /** Assess a transfer given its amount and the source's prior approved-transfer count. */
    public RiskDecision assess(Money amount, int sourceVelocity) {
        if (amount.compareTo(rules.maxAmount()) > 0) {
            return RiskDecision.reject("AMOUNT_LIMIT");
        }
        if (sourceVelocity >= rules.maxVelocity()) {
            return RiskDecision.reject("VELOCITY");
        }
        return RiskDecision.approve();
    }
}
```
`.../domain/TransferScreened.java` (clean domain event):
```java
package com.acme.bank.antifraud.domain;

import org.jmolecules.event.annotation.DomainEvent;

@DomainEvent
public record TransferScreened(String transferId, boolean approved, String reason) {}
```
- [ ] **Step 7: run, PASS** — `gradle :examples:acme-bank:antifraud:test --tests "*RiskEngineTest"` → PASS.
- [ ] **Step 8: format + commit**
```bash
gradle :examples:acme-bank:antifraud:spotlessApply
git add settings.gradle.kts examples/acme-bank/antifraud
git commit -m "feat(antifraud): module + RiskEngine domain (amount-limit + velocity rules)"
```

---

## Task 3: application + persistence + feature-flagged rules + externalization

**Files:** application (`ScreenTransfer` service), persistence (decision JPA + repo + Flyway incl. processed_messages + event_publication), config (`RiskRulesConfig` feature-flagged, `MoneyJacksonConfig`, `ScreeningExternalizationConfig`), `AntifraudApplication`, `application.yaml`.

- [ ] **Step 1: persistence schema** — Flyway `V1__antifraud.sql` (postgresql) creating `screening_decision(transfer_id PK, source_account_id, approved, reason)`, the `acme-messaging` `processed_messages(listener, message_id, processed_at, PRIMARY KEY(listener, message_id))`, and the Modulith `event_publication` table (copy the exact shape from `transfers/V1__transfers.sql`). Provide the oracle mirror.
- [ ] **Step 2: decision persistence** — a `ScreeningDecisionJpaEntity` (`transfer_id` PK, source, approved, reason) + repository with `countBySourceAccountIdAndApprovedTrue(String)` (velocity) + `existsByTransferId`. A `ScreeningStore` out-port + JPA adapter (mirror `JpaLedger`/`JpaTransfers`).
- [ ] **Step 3: `ScreenTransfer` use case** — `application/ScreenTransfer.java`: given (transferId, sourceAccountId, amount), compute `velocity = store.velocity(sourceAccountId)`, `RiskDecision d = riskEngine.assess(amount, velocity)`, persist the decision (idempotent — skip if `existsByTransferId`), and return the `TransferScreened` domain event. The `RiskEngine` bean is built from feature-flagged rules.
- [ ] **Step 4: feature-flagged rules** — `config/RiskRulesConfig.java`: a `@Bean RiskEngine` that reads the OpenFeature `Client` flag `antifraud-strict` (boolean) to pick the strict vs standard `RiskRules` (strict: maxAmount 1,000 USD; standard: 10,000 USD; maxVelocity 5). A demo `FeatureProvider` (`InMemoryProvider`) bean sets `antifraud-strict=false` by default (mirror `demo-service`'s `FeatureFlagsConfig`).
- [ ] **Step 5: Avro consumer + inbox + outbox** — `adapter/in/messaging/TransferRequestedListener.java`: `@KafkaListener(topics = "transfer-requested", groupId = "antifraud")` receiving `com.acme.bank.contracts.avro.TransferRequested`, `@Transactional`, deduped via `Inbox.firstTime("antifraud", event.getTransferId().toString())`; on first time, map the Avro amount via `MoneyMapper.fromAvro`, call `ScreenTransfer`, and `ApplicationEventPublisher.publishEvent(transferScreened)` (Modulith → Avro externalization). Configure the Avro consumer in `application.yaml`: `spring.kafka.consumer.value-deserializer=io.confluent.kafka.serializers.KafkaAvroDeserializer`, `spring.kafka.consumer.properties.specific.avro.reader=true`, and the SR url (injected in tests). Add `MoneyJacksonConfig` + `ScreeningExternalizationConfig` mapping the domain `TransferScreened` → Avro `TransferScreened` routed to topic `transfer-screened` (mirror BANK-2's `TransferExternalizationConfig`, including `spring.modulith.events.kafka.enable-json: false` and `KafkaAvroSerializer` producer).
- [ ] **Step 6: `AntifraudApplication` + `application.yaml`** — Spring Boot main; config with jpa validate, flyway `{vendor}`, the Avro consumer + Avro producer serde.
- [ ] **Step 7: compile** — `gradle :examples:acme-bank:antifraud:compileJava` → BUILD SUCCESSFUL.
- [ ] **Step 8: commit**
```bash
gradle :examples:acme-bank:antifraud:spotlessApply
git add examples/acme-bank/antifraud
git commit -m "feat(antifraud): consume TransferRequested (Avro inbox) -> RiskEngine (feature-flagged) -> emit TransferScreened"
```

---

## Task 4: ArchUnit + screening IT (the proof)

**Files:** `ArchitectureTest.java`, `ScreeningIT.java`.

- [ ] **Step 1: ArchUnit** — mirror `transfers`/`accounts` `ArchitectureTest`: domain free of Spring/JPA/Avro/adapter.
- [ ] **Step 2: screening IT** — `ScreeningIT.java` (Redpanda + Postgres Testcontainers, mirror `TransferExternalizationIT`): produce an Avro `TransferRequested` to topic `transfer-requested` (a raw `KafkaProducer` with `KafkaAvroSerializer` + the container SR url), then await a `TransferScreened` Avro record on topic `transfer-screened` (raw `KafkaAvroDeserializer` consumer). Two test cases:
  - small amount (e.g. 500 USD) → `approved == true`.
  - large amount (e.g. 25,000 USD) → `approved == false`, `reason == "AMOUNT_LIMIT"`.
  Wire the SR url to both the app (consumer + producer via `DynamicPropertyRegistrar` for `spring.kafka.consumer.properties.schema.registry.url` AND `spring.kafka.producer.properties.schema.registry.url`) and the test's raw producer/consumer (passed directly). Use Awaitility (≤ 30s).
- [ ] **Step 3: run** — `gradle :examples:acme-bank:antifraud:test` → all green (RiskEngine unit + ArchUnit + screening IT). The IT proves: consume Avro TransferRequested → screen → emit Avro TransferScreened, idempotently.
- [ ] **Step 4: commit**
```bash
gradle :examples:acme-bank:antifraud:spotlessApply
git add examples/acme-bank/antifraud/src/test
git commit -m "test(antifraud): ArchUnit + screening IT (Avro TransferRequested -> TransferScreened on Redpanda)"
```

---

## Task 5: Full build + ADR

- [ ] **Step 1:** `gradle build` → BUILD SUCCESSFUL.
- [ ] **Step 2:** Create `docs/decisions/0015-antifraud-avro-consumer.md` documenting: deterministic RiskEngine behind a domain port (real ML would replace it), feature-flagged rule sets, Avro consumer (`KafkaAvroDeserializer`) + inbox dedup + Avro outbox emit, the `transfer-requested`→`transfer-screened` hop.
- [ ] **Step 3:** Commit:
```bash
git add docs/decisions/0015-antifraud-avro-consumer.md
git commit -m "docs: ADR 0015 antifraud Avro consumer + RiskEngine"
```

---

## Done criteria for BANK-3

- `gradle build` green; RiskEngine unit-tested; ArchUnit fitness passing.
- antifraud consumes the Avro `TransferRequested` (inbox-deduped), screens via the feature-flagged `RiskEngine`, and emits the Avro `TransferScreened` — proven end-to-end on Redpanda + SR for both approve and reject paths.
- Domain framework/Avro-free.

---

## Self-review notes

- **Spec coverage (§7 antifraud, §9 acme-messaging Avro consumer):** inbox dedup ✓, RiskEngine rules + feature flag ✓, Avro consume + Avro emit ✓. The `acme-messaging` Avro-consumer "improvement" is realized as consumer config (`KafkaAvroDeserializer` + `specific.avro.reader`) — no String-converter clash here (unlike the demo's String path), so no starter code change beyond what exists.
- **Type consistency:** `RiskEngine.assess(Money, int)`, `RiskRules(Money, int)`, `RiskDecision`, domain `TransferScreened` vs Avro `TransferScreened` (FQN in the mapper), `Inbox.firstTime`, `MoneyMapper.fromAvro`. Topic `transfer-requested` (consumed) / `transfer-screened` (emitted).
- **No placeholders.** New risk logic is fully concrete; persistence/externalization mirror the in-repo BANK-1/BANK-2 patterns the implementer reads.
- **Risk:** the Avro consumer + Modulith Avro outbox in one service (consume Avro, emit Avro) — the BANK-2 `enable-json:false` + `MoneyJacksonConfig` pattern applies; the IT is the contract. The consumer needs the SR url on BOTH consumer and producer property namespaces.
