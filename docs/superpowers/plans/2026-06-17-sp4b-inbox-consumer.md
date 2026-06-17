# SP-4b: Idempotent Consumer + Inbox (effectively-once) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** Complete the messaging story with the consumer side: an `acme-messaging` starter providing a JDBC inbox (dedup) + Kafka error-handler→DLT defaults, and a `demo-service` Kafka consumer that builds an order projection idempotently — proving effectively-once (outbox at-least-once + inbox dedup ⇒ single effect) end-to-end against a real Redpanda broker.

**Architecture:** `acme-messaging` ships an `Inbox` abstraction (`firstTime(listener, messageId)` — inserts a `processed_messages` row, returns `false` on duplicate) and a default `DefaultErrorHandler` with a `DeadLetterPublishingRecoverer` (poison messages → `<topic>.DLT`). The demo's `@KafkaListener` on the `orders` topic deserializes the Modulith-externalized JSON `OrderCreated`, dedups via the inbox keyed by order id, and upserts an `order_projection` row in the same transaction. Verification: creating an order drives the full outbox→Kafka→consumer→inbox→projection path (Awaitility on the projection table), and processing the same event twice yields exactly one projection row.

**Tech Stack:** Java 21, Spring Boot 3.5.6, Spring Kafka, JDBC (HikariCP), Testcontainers Redpanda + Postgres, JUnit 5 + AssertJ + Awaitility.

> Spec: `docs/superpowers/specs/2026-06-17-acme-boilerplate-design.md` §5 (acme-messaging, inbox), §7 (effectively-once). Builds on SP-4 (outbox).
> **Scope note:** wire format stays JSON. Avro + Confluent Schema Registry (fork B1) is a documented future enhancement — it needs the Confluent Maven repo + Avro codegen + Schema Registry wiring and does not change the inbox/effectively-once mechanics proven here.
> **Environment (every task):** work from `/Users/npden4ik/Projects/java-boilerplate`; system `gradle` (8.14) NEVER `./gradlew`; JDK 21 toolchain; Maven Central fast; Docker up, Redpanda + Postgres cached. `gradle <module>:spotlessApply` before each commit.

---

## Task 1: `acme-messaging` starter — inbox + error handler

**Files:** settings.gradle.kts (modify); `acme-messaging-spring-boot-autoconfigure` (build.gradle.kts, `Inbox.java`, `JdbcInbox.java`, `MessagingAutoConfiguration.java`, imports, `JdbcInboxTest.java` is an IT — instead unit-light: see Task 2); `acme-messaging-spring-boot-starter/build.gradle.kts`.

- [ ] **Step 1: settings** — add to `include(...)` (after the featureflags starter entry):
```kotlin
    "starters:acme-messaging-spring-boot-autoconfigure",
    "starters:acme-messaging-spring-boot-starter",
```
- [ ] **Step 2: dirs**
```bash
mkdir -p starters/acme-messaging-spring-boot-autoconfigure/src/main/java/com/acme/messaging/autoconfigure \
  starters/acme-messaging-spring-boot-autoconfigure/src/main/resources/META-INF/spring \
  starters/acme-messaging-spring-boot-starter
```
- [ ] **Step 3: autoconfigure build** — `starters/acme-messaging-spring-boot-autoconfigure/build.gradle.kts`:
```kotlin
plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(libs.spring.boot.autoconfigure)
    api(libs.spring.boot.starter.jdbc)
    api(libs.spring.kafka)

    annotationProcessor(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)

    testImplementation(libs.spring.boot.starter.test)
}
```
- [ ] **Step 4: `Inbox` interface** — `.../com/acme/messaging/Inbox.java`:
```java
package com.acme.messaging;

/**
 * Idempotent-consumer inbox. {@link #firstTime} records that a (listener, messageId) pair has been
 * seen; it returns {@code true} the first time and {@code false} for any duplicate, so a handler can
 * skip already-applied side effects. Call it inside the same transaction as the side effect.
 */
public interface Inbox {

    boolean firstTime(String listener, String messageId);
}
```
- [ ] **Step 5: `JdbcInbox`** — `.../com/acme/messaging/JdbcInbox.java`:
```java
package com.acme.messaging;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link Inbox} backed by a {@code processed_messages} table; duplicates trip the primary key. */
public class JdbcInbox implements Inbox {

    private final JdbcTemplate jdbc;

    public JdbcInbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean firstTime(String listener, String messageId) {
        try {
            jdbc.update(
                    "INSERT INTO processed_messages(listener, message_id, processed_at) VALUES (?, ?, ?)",
                    listener,
                    messageId,
                    Timestamp.from(Instant.now()));
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }
}
```
- [ ] **Step 6: auto-config** — `.../com/acme/messaging/autoconfigure/MessagingAutoConfiguration.java`:
```java
package com.acme.messaging.autoconfigure;

import com.acme.messaging.Inbox;
import com.acme.messaging.JdbcInbox;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** Provides a JDBC {@link Inbox} and a default Kafka error handler that routes poison records to a DLT. */
@AutoConfiguration
@ConditionalOnClass(JdbcTemplate.class)
public class MessagingAutoConfiguration {

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean
    public Inbox inbox(JdbcTemplate jdbcTemplate) {
        return new JdbcInbox(jdbcTemplate);
    }

    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    @ConditionalOnMissingBean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> template) {
        // 2 retries, then publish to <topic>.DLT.
        return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), new FixedBackOff(200L, 2L));
    }
}
```
- [ ] **Step 7: imports** — `.../resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
```
com.acme.messaging.autoconfigure.MessagingAutoConfiguration
```
- [ ] **Step 8: thin starter** — `starters/acme-messaging-spring-boot-starter/build.gradle.kts`:
```kotlin
plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(project(":starters:acme-messaging-spring-boot-autoconfigure"))
    api(libs.spring.kafka)
}
```
- [ ] **Step 9: verify** — `gradle :starters:acme-messaging-spring-boot-starter:assemble` → BUILD SUCCESSFUL.
- [ ] **Step 10: commit**
```bash
gradle :starters:acme-messaging-spring-boot-autoconfigure:spotlessApply
git add settings.gradle.kts starters/acme-messaging-spring-boot-autoconfigure starters/acme-messaging-spring-boot-starter
git commit -m "feat(acme-messaging): JDBC inbox (idempotent consumer) + Kafka error-handler/DLT"
```

---

## Task 2: demo — consumer, projection, migrations

**Files:** demo build.gradle.kts (modify); `OrderProjectionListener.java`; migrations `V4__inbox_projection.sql` (postgresql + oracle); application.yaml (consumer config).

- [ ] **Step 1: dep** — in `examples/demo-service/build.gradle.kts` `dependencies { }`, after the featureflags starter:
```kotlin
    implementation(project(":starters:acme-messaging-spring-boot-starter"))
```

- [ ] **Step 2: Postgres migration** — `examples/demo-service/src/main/resources/db/migration/postgresql/V4__inbox_projection.sql`:
```sql
CREATE TABLE processed_messages (
    listener     VARCHAR(128) NOT NULL,
    message_id   VARCHAR(128) NOT NULL,
    processed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (listener, message_id)
);

CREATE TABLE order_projection (
    order_id BIGINT      NOT NULL PRIMARY KEY,
    sku      VARCHAR(64) NOT NULL,
    quantity INTEGER     NOT NULL
);
```

- [ ] **Step 3: Oracle migration (reference)** — `examples/demo-service/src/main/resources/db/migration/oracle/V4__inbox_projection.sql`:
```sql
CREATE TABLE processed_messages (
    listener     VARCHAR2(128) NOT NULL,
    message_id   VARCHAR2(128) NOT NULL,
    processed_at TIMESTAMP(6)  NOT NULL,
    PRIMARY KEY (listener, message_id)
);

CREATE TABLE order_projection (
    order_id NUMBER(19)   NOT NULL PRIMARY KEY,
    sku      VARCHAR2(64) NOT NULL,
    quantity NUMBER(10)   NOT NULL
);
```

- [ ] **Step 4: consumer** — `examples/demo-service/src/main/java/com/acme/demo/OrderProjectionListener.java`:
```java
package com.acme.demo;

import com.acme.messaging.Inbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes externalized {@code OrderCreated} JSON from the {@code orders} topic and builds an order
 * read-model idempotently: the inbox dedups by order id, so redelivery applies the projection once.
 */
@Component
public class OrderProjectionListener {

    private static final String LISTENER = "order-projection";

    private final Inbox inbox;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OrderProjectionListener(Inbox inbox, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.inbox = inbox;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @KafkaListener(topics = "orders", groupId = "demo-order-projection")
    @Transactional
    public void on(String json) throws Exception {
        OrderCreated event = mapper.readValue(json, OrderCreated.class);
        if (inbox.firstTime(LISTENER, String.valueOf(event.orderId()))) {
            jdbc.update(
                    "INSERT INTO order_projection(order_id, sku, quantity) VALUES (?, ?, ?)",
                    event.orderId(),
                    event.sku(),
                    event.quantity());
        }
    }
}
```

- [ ] **Step 5: consumer config** — in `examples/demo-service/src/main/resources/application.yaml`, under the existing `spring:` mapping add a `kafka` block (keep all existing keys):
```yaml
  kafka:
    consumer:
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
```

- [ ] **Step 6: verify compile** — `gradle :examples:demo-service:compileJava` → BUILD SUCCESSFUL.

- [ ] **Step 7: commit**
```bash
gradle :examples:demo-service:spotlespotlessApply 2>/dev/null; gradle :examples:demo-service:spotlessApply
git add examples/demo-service
git commit -m "feat(demo): idempotent OrderCreated consumer building an order projection"
```

---

## Task 3: demo — effectively-once integration tests

**Files:** `InboxIT.java`, `OrderProjectionIT.java`.

- [ ] **Step 1: inbox dedup IT** — `examples/demo-service/src/test/java/com/acme/demo/InboxIT.java`:
```java
package com.acme.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.messaging.Inbox;
import com.acme.test.PostgresTestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(PostgresTestcontainersConfiguration.class)
class InboxIT {

    @Autowired
    Inbox inbox;

    @Test
    void firstTimeIsTrueThenFalseForDuplicate() {
        assertThat(inbox.firstTime("test-listener", "msg-1")).isTrue();
        assertThat(inbox.firstTime("test-listener", "msg-1")).isFalse();
        assertThat(inbox.firstTime("test-listener", "msg-2")).isTrue();
    }
}
```

- [ ] **Step 2: end-to-end projection IT** — `examples/demo-service/src/test/java/com/acme/demo/OrderProjectionIT.java`:
```java
package com.acme.demo;

import static org.assertj.core.api.Assertions.assertThat;

import an.awesome.pipelinr.Pipeline;
import com.acme.test.PostgresTestcontainersConfiguration;
import com.acme.test.RedpandaTestcontainersConfiguration;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import({PostgresTestcontainersConfiguration.class, RedpandaTestcontainersConfiguration.class})
class OrderProjectionIT {

    @Autowired
    Pipeline pipeline;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void orderCreationFlowsThroughKafkaToTheProjectionExactlyOnce() {
        Long id = pipeline.send(new CreateOrderCommand("SKU-PROJ", 5));

        // outbox -> Kafka -> consumer -> inbox -> projection (async); wait for the row.
        Awaitility.await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
            Integer count = jdbc.queryForObject(
                    "SELECT count(*) FROM order_projection WHERE order_id = ?", Integer.class, id);
            assertThat(count).isEqualTo(1);
        });

        String sku = jdbc.queryForObject(
                "SELECT sku FROM order_projection WHERE order_id = ?", String.class, id);
        assertThat(sku).isEqualTo("SKU-PROJ");
    }
}
```

- [ ] **Step 3: dedup at the consumer (idempotent re-apply)** — add to `OrderProjectionIT.java` a second test that calls the listener twice with the same JSON and asserts a single row. Add this method to the class (and autowire the listener + ObjectMapper):
```java
    @Autowired
    OrderProjectionListener listener;

    @Autowired
    com.fasterxml.jackson.databind.ObjectMapper mapper;

    @Test
    void duplicateDeliveryAppliesProjectionOnce() throws Exception {
        String json = mapper.writeValueAsString(new OrderCreated(987654L, "SKU-DUP", 1));

        listener.on(json);
        listener.on(json); // redelivery

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM order_projection WHERE order_id = ?", Integer.class, 987654L);
        assertThat(count).isEqualTo(1);
    }
```

- [ ] **Step 4: run** — `gradle :examples:demo-service:test --tests "*InboxIT" --tests "*OrderProjectionIT"` → PASS.
> Debug: if the projection row never appears — confirm `@KafkaListener` is active (Spring Boot auto-enables it with spring-kafka), the topic is `orders`, the consumer group reads `earliest`, and the value deserializer is `StringDeserializer` (Modulith externalizes JSON as a string). If `mapper.readValue` fails — the externalized JSON should be `{"orderId":...,"sku":...,"quantity":...}` matching the `OrderCreated` record. If the duplicate test inserts twice — the inbox `firstTime` isn't deduping (check the `processed_messages` PK + that both calls use the same listener+id).

- [ ] **Step 5: full demo suite** — `gradle :examples:demo-service:test` → all green.
- [ ] **Step 6: commit**
```bash
gradle :examples:demo-service:spotlessApply
git add examples/demo-service/src/test
git commit -m "test(demo): effectively-once — projection via Kafka + inbox dedup"
```

---

## Task 4: Full build + ADR

- [ ] **Step 1:** `gradle build` → BUILD SUCCESSFUL. Fix Spotless via `spotlessApply`; debug real failures; BLOCKED if stuck.
- [ ] **Step 2:** Create `docs/decisions/0008-inbox-effectively-once.md`:
```markdown
---
status: accepted
date: 2026-06-17
---

# Idempotent consumer (inbox) → effectively-once

## Context and Problem Statement

The Modulith outbox (SP-4) is at-least-once; Kafka can redeliver. Consumers must apply side effects
exactly once without distributed transactions.

## Decision Outcome

- The `acme-messaging` starter provides a JDBC `Inbox`: `firstTime(listener, messageId)` inserts a
  `processed_messages` row (PK `(listener, message_id)`) and returns `false` on duplicate, so a
  handler skips already-applied effects. Called inside the handler transaction, the dedup marker and
  the side effect commit atomically — effectively-once on top of at-least-once delivery.
- A default `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` routes poison records to
  `<topic>.DLT` after bounded retries.
- The demo consumes externalized `OrderCreated` and builds an `order_projection` read-model
  idempotently; an IT proves the full outbox→Kafka→inbox→projection path applies exactly once, and a
  redelivery applies the projection once.
- Wire format stays JSON; Avro + Confluent Schema Registry is a documented future enhancement that
  does not change these mechanics.

Full detail: `docs/superpowers/specs/2026-06-17-acme-boilerplate-design.md` §5 (acme-messaging).
```
- [ ] **Step 3:** Commit:
```bash
git add docs/decisions/0008-inbox-effectively-once.md
git commit -m "docs: ADR 0008 idempotent consumer inbox + effectively-once"
```

---

## Done criteria for SP-4b

- `gradle build` green (all modules, Spotless, all tests).
- `acme-messaging` starter: JDBC inbox + Kafka error-handler/DLT.
- demo consumes `OrderCreated` idempotently into a projection; ITs prove the full effectively-once path and dedup on redelivery.

---

## Self-review notes

- **Spec coverage (§5 acme-messaging, §7):** inbox dedup ✓, idempotent consumer ✓, error-handler→DLT ✓, effectively-once (outbox + inbox) proven ✓. Deferred-documented: Avro + Schema Registry, tiered retry topics (`@RetryableTopic`), per-key ordering knobs.
- **Type consistency:** `Inbox.firstTime(String,String)` used by `JdbcInbox`, `OrderProjectionListener`, `InboxIT`; `OrderCreated(orderId, sku, quantity)` round-trips via Jackson; projection columns match the insert.
- **No placeholders.** Concrete throughout.
- **Effectively-once proof:** the duplicate-delivery test (`duplicateDeliveryAppliesProjectionOnce`) is the meaningful assertion — same event applied twice yields one row; the e2e test proves the live broker path.
- **Transaction note:** the listener is `@Transactional`, so the inbox insert and the projection insert commit atomically; a crash between them cannot leave a dedup marker without the effect.
