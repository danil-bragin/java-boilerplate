# BANK-2: transfers service + Avro outbox externalization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Build the `transfers` service — the saga-coordinating `Transfer` aggregate (state machine) with an `InitiateTransfer` CQRS command — and demonstrate **Avro outbox externalization**: a clean domain event published inside the command transaction is mapped to an Avro integration event at the Spring Modulith externalization boundary and published to Kafka via `KafkaAvroSerializer` + Schema Registry. The full saga consume-side (advancing on screening/posting results) is modeled in the domain (unit-tested transitions) and wired incrementally in BANK-3/BANK-5.

**Architecture:** Hexagonal `transfers`: `domain` (`TransferId`, `TransferStatus`, `Transfer` aggregate state machine `REQUESTED→SCREENING→APPROVED/REJECTED→POSTING→COMPLETED/FAILED`, the `TransferRequested` clean domain event), `application` (`InitiateTransfer` `StronglyConsistent` command → persist `Transfer(REQUESTED)` + `ApplicationEventPublisher.publishEvent(domain event)`), `adapter/out` (JPA persistence + a Spring Modulith `EventExternalizationConfiguration` that `.mapping`s the domain event → the `bank-contracts` Avro `TransferRequested` and routes it to the `transfers` topic; the Kafka producer uses `KafkaAvroSerializer`). Domain never imports Avro/Modulith — the mapping lives in an adapter config.

**Tech Stack:** Java 21, Spring Boot 3.5.6, Spring Modulith 1.4.6 (events + Kafka externalization), Confluent Avro serde, `acme-money`/`acme-persistence`/`acme-cqrs`/`acme-outbox` starters, `bank-contracts`, Testcontainers Redpanda + Postgres, JUnit5 + AssertJ + Awaitility, ArchUnit.

> Spec: `docs/superpowers/specs/2026-06-18-acme-bank-design.md` §7/§8/§9. Builds on BANK-0/0.5/1.
> **Modulith externalization API (1.4.6, verified):** `EventExternalizationConfiguration.externalizing().select(<Selector>).mapping(Class<T>, Function<T,Object>).route(Class<T>, Function<T,RoutingTarget>).build()`. Select by event type (no `@Externalized` annotation on the domain event — keep the domain clean). `RoutingTarget.forTarget("transfers").andKey(key)`.
> **Environment (every task):** work from `/Users/npden4ik/Projects/java-boilerplate`; system `gradle` (8.14) NEVER `./gradlew`; JDK 21 toolchain; Docker up, Redpanda + Postgres cached; Confluent repo reachable (slow). `gradle <module>:spotlessApply` before each commit. Spotless active; exclude generated Avro from style.

---

## Task 1: bank-contracts — add the saga event schemas

**Files:** new `.avsc` files in `bank-contracts/src/main/avro/`.

- [ ] **Step 1:** Create the remaining integration-event schemas (referencing the existing `Money.avsc`). Create:

`PostingRequested.avsc`:
```json
{
  "namespace": "com.acme.bank.contracts.avro",
  "type": "record",
  "name": "PostingRequested",
  "fields": [
    { "name": "transferId", "type": "string" },
    { "name": "sourceAccountId", "type": "string" },
    { "name": "destinationAccountId", "type": "string" },
    { "name": "amount", "type": "com.acme.bank.contracts.avro.Money" }
  ]
}
```
`LedgerPosted.avsc`:
```json
{
  "namespace": "com.acme.bank.contracts.avro",
  "type": "record",
  "name": "LedgerPosted",
  "fields": [
    { "name": "transferId", "type": "string" }
  ]
}
```
`PostingRejected.avsc`:
```json
{
  "namespace": "com.acme.bank.contracts.avro",
  "type": "record",
  "name": "PostingRejected",
  "fields": [
    { "name": "transferId", "type": "string" },
    { "name": "reason", "type": "string" }
  ]
}
```
`TransferScreened.avsc`:
```json
{
  "namespace": "com.acme.bank.contracts.avro",
  "type": "record",
  "name": "TransferScreened",
  "fields": [
    { "name": "transferId", "type": "string" },
    { "name": "approved", "type": "boolean" },
    { "name": "reason", "type": ["null", "string"], "default": null }
  ]
}
```
`TransferFailed.avsc`:
```json
{
  "namespace": "com.acme.bank.contracts.avro",
  "type": "record",
  "name": "TransferFailed",
  "fields": [
    { "name": "transferId", "type": "string" },
    { "name": "reason", "type": "string" }
  ]
}
```
- [ ] **Step 2:** Verify codegen — `gradle :examples:acme-bank:bank-contracts:compileJava` → BUILD SUCCESSFUL (generates all new types).
- [ ] **Step 3:** Commit:
```bash
git add examples/acme-bank/bank-contracts/src/main/avro
git commit -m "feat(bank-contracts): saga event schemas (PostingRequested/Rejected, LedgerPosted, TransferScreened/Failed)"
```

---

## Task 2: transfers module + Transfer aggregate state machine (TDD)

**Files:** settings (modify), `transfers/build.gradle.kts`, domain (`TransferId`, `TransferStatus`, `Transfer`, `TransferRequested`), test `TransferTest.java`.

- [ ] **Step 1: settings** — add `"examples:acme-bank:transfers",` to `include(...)`.
- [ ] **Step 2: dirs**
```bash
mkdir -p examples/acme-bank/transfers/src/main/java/com/acme/bank/transfers/domain \
  examples/acme-bank/transfers/src/main/java/com/acme/bank/transfers/application \
  examples/acme-bank/transfers/src/main/java/com/acme/bank/transfers/adapter/out/persistence \
  examples/acme-bank/transfers/src/main/java/com/acme/bank/transfers/adapter/out/messaging \
  examples/acme-bank/transfers/src/main/resources/db/migration/postgresql \
  examples/acme-bank/transfers/src/main/resources/db/migration/oracle \
  examples/acme-bank/transfers/src/test/java/com/acme/bank/transfers/domain \
  examples/acme-bank/transfers/src/test/java/com/acme/bank/transfers/application
```
- [ ] **Step 3: build script** — `examples/acme-bank/transfers/build.gradle.kts`:
```kotlin
plugins {
    id("acme.bank-service-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(project(":platform:acme-bom")))
    implementation(project(":starters:acme-money"))
    implementation(project(":starters:acme-persistence-spring-boot-starter"))
    implementation(project(":starters:acme-cqrs-spring-boot-starter"))
    implementation(project(":starters:acme-outbox-spring-boot-starter"))
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
> Add `awaitility` to the catalog if missing: `awaitility = { module = "org.awaitility:awaitility" }` (Boot-managed) — check `gradle/libs.versions.toml` and add if absent (it is used by other ITs already, likely present).
- [ ] **Step 4: failing test** — `examples/acme-bank/transfers/src/test/java/com/acme/bank/transfers/domain/TransferTest.java`:
```java
package com.acme.bank.transfers.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.money.Assets;
import com.acme.money.Money;
import org.junit.jupiter.api.Test;

class TransferTest {

    private Transfer requested() {
        return Transfer.request(
                new TransferId("t-1"), "acc-src", "acc-dst", Money.of("100.00", Assets.USD), "alice");
    }

    @Test
    void requestStartsInRequested() {
        assertThat(requested().status()).isEqualTo(TransferStatus.REQUESTED);
    }

    @Test
    void approveThenPostThenComplete() {
        Transfer t = requested();
        t.approve();
        assertThat(t.status()).isEqualTo(TransferStatus.APPROVED);
        t.markPosting();
        assertThat(t.status()).isEqualTo(TransferStatus.POSTING);
        t.complete();
        assertThat(t.status()).isEqualTo(TransferStatus.COMPLETED);
    }

    @Test
    void rejectFromRequestedFails() {
        Transfer t = requested();
        t.reject("FRAUD");
        assertThat(t.status()).isEqualTo(TransferStatus.FAILED);
    }

    @Test
    void illegalTransitionThrows() {
        Transfer t = requested();
        assertThatThrownBy(t::complete).isInstanceOf(IllegalStateException.class);
    }
}
```
- [ ] **Step 5: run, FAIL** — `gradle :examples:acme-bank:transfers:test --tests "*TransferTest"` → FAIL.
- [ ] **Step 6: domain** — create:

`.../domain/TransferId.java`:
```java
package com.acme.bank.transfers.domain;

import java.util.Objects;
import org.jmolecules.ddd.annotation.ValueObject;

@ValueObject
public record TransferId(String value) {
    public TransferId {
        Objects.requireNonNull(value, "transfer id");
        if (value.isBlank()) {
            throw new IllegalArgumentException("transfer id must not be blank");
        }
    }
}
```
`.../domain/TransferStatus.java`:
```java
package com.acme.bank.transfers.domain;

public enum TransferStatus {
    REQUESTED,
    SCREENING,
    APPROVED,
    REJECTED,
    POSTING,
    COMPLETED,
    FAILED
}
```
`.../domain/TransferRequested.java` (clean domain event — no Modulith/Avro):
```java
package com.acme.bank.transfers.domain;

import com.acme.money.Money;
import org.jmolecules.event.annotation.DomainEvent;

@DomainEvent
public record TransferRequested(
        String transferId, String sourceAccountId, String destinationAccountId, Money amount, String requestedBy) {}
```
`.../domain/Transfer.java`:
```java
package com.acme.bank.transfers.domain;

import com.acme.money.Money;
import java.util.Objects;
import org.jmolecules.ddd.annotation.AggregateRoot;
import org.jmolecules.ddd.annotation.Identity;

/** Coordinating saga aggregate for one money transfer. Enforces the legal state transitions. */
@AggregateRoot
public class Transfer {

    @Identity
    private final TransferId id;

    private final String sourceAccountId;
    private final String destinationAccountId;
    private final Money amount;
    private final String requestedBy;
    private TransferStatus status;
    private String failureReason;

    private Transfer(
            TransferId id, String source, String destination, Money amount, String requestedBy, TransferStatus status) {
        this.id = Objects.requireNonNull(id, "id");
        this.sourceAccountId = Objects.requireNonNull(source, "source");
        this.destinationAccountId = Objects.requireNonNull(destination, "destination");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.requestedBy = Objects.requireNonNull(requestedBy, "requestedBy");
        this.status = status;
    }

    public static Transfer request(
            TransferId id, String source, String destination, Money amount, String requestedBy) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("transfer amount must be positive");
        }
        return new Transfer(id, source, destination, amount, requestedBy, TransferStatus.REQUESTED);
    }

    public TransferRequested toRequestedEvent() {
        return new TransferRequested(id.value(), sourceAccountId, destinationAccountId, amount, requestedBy);
    }

    public void approve() {
        requireStatus(TransferStatus.REQUESTED, TransferStatus.SCREENING);
        this.status = TransferStatus.APPROVED;
    }

    public void reject(String reason) {
        requireStatus(TransferStatus.REQUESTED, TransferStatus.SCREENING);
        this.status = TransferStatus.FAILED;
        this.failureReason = reason;
    }

    public void markPosting() {
        requireStatus(TransferStatus.APPROVED);
        this.status = TransferStatus.POSTING;
    }

    public void complete() {
        requireStatus(TransferStatus.POSTING);
        this.status = TransferStatus.COMPLETED;
    }

    public void fail(String reason) {
        requireStatus(TransferStatus.POSTING, TransferStatus.APPROVED);
        this.status = TransferStatus.FAILED;
        this.failureReason = reason;
    }

    private void requireStatus(TransferStatus... allowed) {
        for (TransferStatus s : allowed) {
            if (this.status == s) {
                return;
            }
        }
        throw new IllegalStateException("illegal transition from " + status);
    }

    public TransferId id() {
        return id;
    }

    public TransferStatus status() {
        return status;
    }

    public Money amount() {
        return amount;
    }

    public String sourceAccountId() {
        return sourceAccountId;
    }

    public String destinationAccountId() {
        return destinationAccountId;
    }

    public String requestedBy() {
        return requestedBy;
    }

    public String failureReason() {
        return failureReason;
    }
}
```
- [ ] **Step 7: run, PASS** — `gradle :examples:acme-bank:transfers:test --tests "*TransferTest"` → PASS.
- [ ] **Step 8: format + commit**
```bash
gradle :examples:acme-bank:transfers:spotlessApply
git add settings.gradle.kts gradle/libs.versions.toml examples/acme-bank/transfers
git commit -m "feat(transfers): Transfer saga aggregate (state machine) + domain event"
```

---

## Task 3: InitiateTransfer use case + persistence + Avro externalization

**Files:** application (`InitiateTransferCommand`, `InitiateTransferHandler`, port `Transfers`), persistence (JPA + repo + adapter + Flyway incl. Modulith event_publication), messaging (`TransferAvroMapper`, `TransferExternalizationConfig`), `TransfersApplication`, `application.yaml`.

- [ ] **Step 1: port + command** —

`.../domain/Transfers.java` (out-port):
```java
package com.acme.bank.transfers.domain;

import java.util.Optional;

public interface Transfers {
    void save(Transfer transfer);

    Optional<Transfer> findById(TransferId id);

    boolean exists(TransferId id);
}
```
`.../application/InitiateTransferCommand.java`:
```java
package com.acme.bank.transfers.application;

import an.awesome.pipelinr.Command;
import com.acme.cqrs.StronglyConsistent;
import com.acme.money.Money;

public record InitiateTransferCommand(
        String transferId, String sourceAccountId, String destinationAccountId, Money amount, String requestedBy)
        implements Command<String>, StronglyConsistent {}
```
`.../application/InitiateTransferHandler.java`:
```java
package com.acme.bank.transfers.application;

import an.awesome.pipelinr.Command;
import com.acme.bank.transfers.domain.Transfer;
import com.acme.bank.transfers.domain.TransferId;
import com.acme.bank.transfers.domain.Transfers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class InitiateTransferHandler implements Command.Handler<InitiateTransferCommand, String> {

    private final Transfers transfers;
    private final ApplicationEventPublisher events;

    public InitiateTransferHandler(Transfers transfers, ApplicationEventPublisher events) {
        this.transfers = transfers;
        this.events = events;
    }

    @Override
    public String handle(InitiateTransferCommand command) {
        TransferId id = new TransferId(command.transferId());
        if (transfers.exists(id)) {
            return id.value(); // idempotent initiate
        }
        Transfer transfer = Transfer.request(
                id,
                command.sourceAccountId(),
                command.destinationAccountId(),
                command.amount(),
                command.requestedBy());
        transfers.save(transfer);
        // Published inside the StronglyConsistent tx -> Modulith writes the outbox row, externalizes as Avro after commit.
        events.publishEvent(transfer.toRequestedEvent());
        return id.value();
    }
}
```
- [ ] **Step 2: persistence** — JPA entity + repo + adapter (mirror the accounts pattern; `Transfer` maps to a `transfers` table with the amount as `MoneyAmount`):

`.../adapter/out/persistence/TransferJpaEntity.java`:
```java
package com.acme.bank.transfers.adapter.out.persistence;

import com.acme.persistence.MoneyAmount;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "transfer")
class TransferJpaEntity {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "source_account_id", nullable = false)
    private String sourceAccountId;

    @Column(name = "destination_account_id", nullable = false)
    private String destinationAccountId;

    @Embedded
    private MoneyAmount amount;

    @Column(name = "requested_by", nullable = false)
    private String requestedBy;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "failure_reason")
    private String failureReason;

    protected TransferJpaEntity() {}

    TransferJpaEntity(
            String id,
            String sourceAccountId,
            String destinationAccountId,
            MoneyAmount amount,
            String requestedBy,
            String status,
            String failureReason) {
        this.id = id;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.amount = amount;
        this.requestedBy = requestedBy;
        this.status = status;
        this.failureReason = failureReason;
    }

    String getId() {
        return id;
    }

    String getSourceAccountId() {
        return sourceAccountId;
    }

    String getDestinationAccountId() {
        return destinationAccountId;
    }

    MoneyAmount getAmount() {
        return amount;
    }

    String getRequestedBy() {
        return requestedBy;
    }

    String getStatus() {
        return status;
    }

    String getFailureReason() {
        return failureReason;
    }
}
```
`.../adapter/out/persistence/TransferJpaRepository.java`:
```java
package com.acme.bank.transfers.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface TransferJpaRepository extends JpaRepository<TransferJpaEntity, String> {}
```
`.../adapter/out/persistence/JpaTransfers.java`:
```java
package com.acme.bank.transfers.adapter.out.persistence;

import com.acme.bank.transfers.domain.Transfer;
import com.acme.bank.transfers.domain.TransferId;
import com.acme.bank.transfers.domain.TransferStatus;
import com.acme.bank.transfers.domain.Transfers;
import com.acme.money.Assets;
import com.acme.persistence.MoneyAmount;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class JpaTransfers implements Transfers {

    private final TransferJpaRepository repository;

    JpaTransfers(TransferJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(Transfer t) {
        repository.save(new TransferJpaEntity(
                t.id().value(),
                t.sourceAccountId(),
                t.destinationAccountId(),
                MoneyAmount.from(t.amount()),
                t.requestedBy(),
                t.status().name(),
                t.failureReason()));
    }

    @Override
    public Optional<Transfer> findById(TransferId id) {
        return repository.findById(id.value()).map(e -> rehydrate(e));
    }

    @Override
    public boolean exists(TransferId id) {
        return repository.existsById(id.value());
    }

    private Transfer rehydrate(TransferJpaEntity e) {
        // For BANK-2 only the REQUESTED path is exercised; rehydration of later states is added in BANK-5.
        Transfer t = Transfer.request(
                new TransferId(e.getId()),
                e.getSourceAccountId(),
                e.getDestinationAccountId(),
                e.getAmount().toMoney(Assets::of),
                e.getRequestedBy());
        return t;
    }
}
```
> Note: rehydration to non-REQUESTED states is deferred to BANK-5 (when the consume-side advances persisted transfers). Add a clarifying comment; do not over-build now.
- [ ] **Step 3: Avro mapper + externalization config** —

`.../adapter/out/messaging/TransferAvroMapper.java`:
```java
package com.acme.bank.transfers.adapter.out.messaging;

import com.acme.bank.contracts.MoneyMapper;
import com.acme.bank.contracts.avro.TransferRequested;
import com.acme.bank.transfers.domain.TransferRequested.;
```
> CORRECT the import: the domain event is `com.acme.bank.transfers.domain.TransferRequested`; the Avro type is `com.acme.bank.contracts.avro.TransferRequested`. Use fully-qualified names to disambiguate. Final mapper:
```java
package com.acme.bank.transfers.adapter.out.messaging;

import com.acme.bank.contracts.MoneyMapper;

/** Maps the clean domain {@code TransferRequested} event to its Avro integration contract. */
public final class TransferAvroMapper {

    private TransferAvroMapper() {}

    public static com.acme.bank.contracts.avro.TransferRequested toAvro(
            com.acme.bank.transfers.domain.TransferRequested event) {
        return com.acme.bank.contracts.avro.TransferRequested.newBuilder()
                .setTransferId(event.transferId())
                .setSourceAccountId(event.sourceAccountId())
                .setDestinationAccountId(event.destinationAccountId())
                .setAmount(MoneyMapper.toAvro(event.amount()))
                .setRequestedBy(event.requestedBy())
                .setRequestedAt(java.time.Instant.now().toEpochMilli())
                .build();
    }
}
```
`.../adapter/out/messaging/TransferExternalizationConfig.java`:
```java
package com.acme.bank.transfers.adapter.out.messaging;

import com.acme.bank.transfers.domain.TransferRequested;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;

/** Maps the domain {@code TransferRequested} to the Avro contract and routes it to the {@code transfers} topic. */
@Configuration
class TransferExternalizationConfig {

    @Bean
    EventExternalizationConfiguration eventExternalizationConfiguration() {
        return EventExternalizationConfiguration.externalizing()
                .select(event -> event instanceof TransferRequested)
                .mapping(TransferRequested.class, TransferAvroMapper::toAvro)
                .route(
                        TransferRequested.class,
                        event -> RoutingTarget.forTarget("transfers").andKey(event.transferId()))
                .build();
    }
}
```
> If the resolved Modulith 1.4.6 `Selector.select(...)` / `route(...)` signatures differ, adapt minimally to: select `TransferRequested` domain events, map each to `TransferAvroMapper.toAvro`, route to topic `transfers` keyed by `transferId`. Report the exact API used.
- [ ] **Step 4: Spring Boot app + config** —

`.../TransfersApplication.java`:
```java
package com.acme.bank.transfers;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TransfersApplication {
    public static void main(String[] args) {
        SpringApplication.run(TransfersApplication.class, args);
    }
}
```
`.../src/main/resources/application.yaml`:
```yaml
spring:
  application:
    name: transfers
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    locations: classpath:db/migration/{vendor}
  kafka:
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: io.confluent.kafka.serializers.KafkaAvroSerializer
```
> The Schema Registry URL (`spring.kafka.producer.properties.schema.registry.url`) is supplied in the IT via the Redpanda container; at runtime set it to the real SR.
- [ ] **Step 5: Flyway** — `.../db/migration/postgresql/V1__transfers.sql`:
```sql
CREATE TABLE transfer (
    id                     VARCHAR(64)    NOT NULL PRIMARY KEY,
    source_account_id      VARCHAR(64)    NOT NULL,
    destination_account_id VARCHAR(64)    NOT NULL,
    amount                 NUMERIC(38,18) NOT NULL,
    asset                  VARCHAR(16)    NOT NULL,
    requested_by           VARCHAR(128)   NOT NULL,
    status                 VARCHAR(16)    NOT NULL,
    failure_reason         VARCHAR(256)
);

CREATE TABLE event_publication (
    id               UUID         NOT NULL PRIMARY KEY,
    listener_id      TEXT         NOT NULL,
    serialized_event TEXT         NOT NULL,
    event_type       TEXT         NOT NULL,
    publication_date TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date  TIMESTAMP WITH TIME ZONE
);
```
And `.../db/migration/oracle/V1__transfers.sql` (Oracle types: `VARCHAR2`, `NUMBER(38,18)`, `RAW(16)` id, `CLOB` serialized_event, `TIMESTAMP(6) WITH TIME ZONE`):
```sql
CREATE TABLE transfer (
    id                     VARCHAR2(64)   NOT NULL PRIMARY KEY,
    source_account_id      VARCHAR2(64)   NOT NULL,
    destination_account_id VARCHAR2(64)   NOT NULL,
    amount                 NUMBER(38,18)  NOT NULL,
    asset                  VARCHAR2(16)   NOT NULL,
    requested_by           VARCHAR2(128)  NOT NULL,
    status                 VARCHAR2(16)   NOT NULL,
    failure_reason         VARCHAR2(256)
);

CREATE TABLE event_publication (
    id               RAW(16)                     NOT NULL PRIMARY KEY,
    listener_id      VARCHAR2(512)               NOT NULL,
    serialized_event CLOB                        NOT NULL,
    event_type       VARCHAR2(512)               NOT NULL,
    publication_date TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    completion_date  TIMESTAMP(6) WITH TIME ZONE
);
```
- [ ] **Step 6: compile** — `gradle :examples:acme-bank:transfers:compileJava` → BUILD SUCCESSFUL.
- [ ] **Step 7: commit**
```bash
gradle :examples:acme-bank:transfers:spotlessApply
git add examples/acme-bank/transfers
git commit -m "feat(transfers): InitiateTransfer use case + JPA persistence + Avro outbox externalization config"
```

---

## Task 4: Avro outbox externalization IT (the proof) + ArchUnit

**Files:** `ArchitectureTest.java`, `TransferExternalizationIT.java`.

- [ ] **Step 1: ArchUnit** — `.../src/test/java/com/acme/bank/transfers/ArchitectureTest.java` (same shape as accounts: domain free of Spring/JPA/Modulith/Avro/adapter):
```java
package com.acme.bank.transfers;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.acme.bank.transfers");

    @Test
    void domainIsFrameworkFree() {
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "org.apache.avro..",
                        "com.acme.bank.contracts.avro..",
                        "..adapter..")
                .check(classes);
    }
}
```
- [ ] **Step 2: externalization IT** — `.../src/test/java/com/acme/bank/transfers/TransferExternalizationIT.java`. It initiates a transfer via the Pipeline and asserts a `TransferRequested` Avro record arrives on the `transfers` Kafka topic, deserialized via `KafkaAvroDeserializer` against the Redpanda Schema Registry:
```java
package com.acme.bank.transfers;

import static org.assertj.core.api.Assertions.assertThat;

import an.awesome.pipelinr.Pipeline;
import com.acme.bank.contracts.avro.TransferRequested;
import com.acme.bank.transfers.application.InitiateTransferCommand;
import com.acme.money.Assets;
import com.acme.money.Money;
import com.acme.test.PostgresTestcontainersConfiguration;
import com.acme.test.RedpandaTestcontainersConfiguration;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DynamicTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.testcontainers.redpanda.RedpandaContainer;

@SpringBootTest
@Import({
    PostgresTestcontainersConfiguration.class,
    RedpandaTestcontainersConfiguration.class,
    TransferExternalizationIT.SchemaRegistryProps.class
})
class TransferExternalizationIT {

    @Autowired
    Pipeline pipeline;

    @Autowired
    RedpandaContainer redpanda;

    @TestConfiguration
    static class SchemaRegistryProps {
        // Point the app's KafkaAvroSerializer + this test consumer at the Redpanda Schema Registry.
        @Bean
        DynamicPropertyRegistrar schemaRegistry(RedpandaContainer redpanda) {
            return registry -> registry.add(
                    "spring.kafka.producer.properties.schema.registry.url", redpanda::getSchemaRegistryAddress);
        }
    }

    @Test
    void initiatingATransferExternalizesTransferRequestedAsAvro() {
        String bootstrap = redpanda.getBootstrapServers().replaceFirst(".*://", "");
        String srUrl = redpanda.getSchemaRegistryAddress();

        String id = pipeline.send(new InitiateTransferCommand(
                "t-ext-1", "acc-src", "acc-dst", Money.of("100.00", Assets.USD), "alice"));
        assertThat(id).isEqualTo("t-ext-1");

        try (Consumer<String, TransferRequested> consumer = newConsumer(bootstrap, srUrl)) {
            consumer.subscribe(List.of("transfers"));
            Awaitility.await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
                ConsumerRecords<String, TransferRequested> records = consumer.poll(Duration.ofMillis(500));
                TransferRequested received = null;
                for (ConsumerRecord<String, TransferRequested> r : records) {
                    received = r.value();
                }
                assertThat(received).isNotNull();
                assertThat(received.getTransferId().toString()).isEqualTo("t-ext-1");
                assertThat(received.getAmount().getAmount().toString()).isEqualTo("100");
                assertThat(received.getAmount().getAsset().toString()).isEqualTo("USD");
            });
        }
    }

    private static Consumer<String, TransferRequested> newConsumer(String bootstrap, String srUrl) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "transfer-ext-it");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, srUrl);
        props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
        return new KafkaConsumer<>(props);
    }
}
```
> Remove the unused `DynamicTest` import if Spotless flags it. The amount asserts `"100"` because `Money.of("100.00").toAmountString()` strips trailing zeros (matches the MoneyMapper). If the externalization doesn't fire: confirm the event is published inside the StronglyConsistent tx (it is — `InitiateTransfer` is `StronglyConsistent`), `spring-modulith-events-kafka` is present (via `acme-outbox`), and the producer `schema.registry.url` property is set (the `DynamicPropertyRegistrar` does this). If `EventExternalizationConfiguration` API differs, adapt Task 3 Step 3 and report.
- [ ] **Step 3: run** — `gradle :examples:acme-bank:transfers:test` → all green (Transfer unit tests + ArchUnit + the externalization IT). The IT is the proof of Avro outbox externalization end-to-end on a real broker + Schema Registry.
- [ ] **Step 4: commit**
```bash
gradle :examples:acme-bank:transfers:spotlessApply
git add examples/acme-bank/transfers/src/test
git commit -m "test(transfers): ArchUnit + Avro outbox externalization IT (TransferRequested on Redpanda + SR)"
```

---

## Task 5: Full build + ADR

- [ ] **Step 1:** `gradle build` → BUILD SUCCESSFUL.
- [ ] **Step 2:** Create `docs/decisions/0014-avro-outbox-externalization.md`:
```markdown
---
status: accepted
date: 2026-06-18
---

# Avro outbox externalization (transfers)

## Decision Outcome

- A service emits a clean domain event (`TransferRequested`, no Modulith/Avro in the domain) from a
  `StronglyConsistent` command. Spring Modulith persists it to the outbox in the same transaction and,
  after commit, externalizes it. An `EventExternalizationConfiguration` `.mapping(DomainEvent.class,
  domain -> avroIntegrationEvent)` converts it to the `bank-contracts` Avro record and routes it to the
  topic (keyed by transfer id); the Kafka producer uses `KafkaAvroSerializer` against the Schema Registry.
- The domain → Avro mapping lives in an adapter (`TransferAvroMapper` / `TransferExternalizationConfig`),
  keeping the domain framework- and wire-format-free (ArchUnit-enforced).
- Verified end-to-end (`TransferExternalizationIT`): initiating a transfer produces a `TransferRequested`
  Avro record on the `transfers` topic, deserialized via `KafkaAvroDeserializer` against the Redpanda
  Schema Registry — schema auto-registered, money as `{amount,asset}` strings (no float).

Full design: `docs/superpowers/specs/2026-06-18-acme-bank-design.md` §7/§8/§9.
```
- [ ] **Step 3:** Commit:
```bash
git add docs/decisions/0014-avro-outbox-externalization.md
git commit -m "docs: ADR 0014 Avro outbox externalization"
```

---

## Done criteria for BANK-2

- `gradle build` green; transfers Transfer state machine unit-tested; ArchUnit fitness passing.
- Avro outbox externalization proven: initiating a transfer externalizes `TransferRequested` (Avro) to the `transfers` Kafka topic via Modulith + `KafkaAvroSerializer` + Schema Registry.
- Domain is framework/Avro-free; the domain→Avro mapping lives in an adapter.

---

## Self-review notes

- **Spec coverage:** §7 Transfer saga aggregate (state machine) ✓ (consume-side advancement wired in BANK-3/5), §8 Avro contracts ✓ (saga schemas added), §9 acme-outbox Avro externalization ✓ (the central improvement, proven). Persistence/Money mapping reused from BANK-1.
- **Type consistency:** domain `TransferRequested` vs Avro `TransferRequested` disambiguated by FQN in the mapper; `InitiateTransferCommand`/`Handler`; `Transfers` port; `MoneyMapper` from bank-contracts. State enum matches the saga.
- **No placeholders.** Concrete code/SQL/IT.
- **Risk:** the Modulith `EventExternalizationConfiguration` selector/route API — Task 3 Step 3 + Task 4 note call out adaptation against the verified 1.4.6 signatures (`externalizing().select(Predicate).mapping(Class,fn).route(Class,fn).build()`). The IT is the contract.
