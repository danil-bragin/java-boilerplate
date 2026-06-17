# SP-1: Persistence Starter (Oracle-first, DB-agnostic) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a reusable `acme-persistence` Spring Boot starter (JPA/Hibernate base + clock-backed JPA auditing + Flyway with `{vendor}` migration dirs, Oracle-first but DB-agnostic) plus a shared `acme-test-support` Testcontainers module, and prove it end-to-end by persisting/retrieving an `Order` in `demo-service` against a real database.

**Architecture:** The persistence autoconfigure module ships an `@MappedSuperclass` `AuditedEntity` (createdAt/updatedAt/`@Version`), a `Clock` bean, and a clock-backed `DateTimeProvider` wired into `@EnableJpaAuditing` — all overridable. The thin starter aggregates Spring Data JPA, Flyway (+ postgresql & oracle DB modules), and JDBC drivers (ojdbc11 primary, postgresql for swap). `acme-test-support` provides a `@TestConfiguration` exposing a `@ServiceConnection` `PostgreSQLContainer` so integration tests run against a real DB with zero per-test wiring. The Oracle dialect is the documented reference; tests run on Postgres (its Testcontainers image is cached locally — the Oracle Free image is large and not available on this network, so Oracle verification is a deferred opt-in profile).

**Tech Stack:** Java 21, Spring Boot 3.5.6, Spring Data JPA/Hibernate, Flyway 11 (Boot-managed), Testcontainers (Boot-managed) + `postgres:16-alpine`, JUnit 5 + AssertJ.

> Spec: `docs/superpowers/specs/2026-06-17-acme-boilerplate-design.md` (§3, §5 acme-persistence, §7 invariants). Builds on SP-0 (`docs/superpowers/plans/2026-06-17-sp0-monorepo-skeleton.md`).
> **Environment (every task):** work from `/Users/npden4ik/Projects/java-boilerplate`; use system `gradle` (8.14) NEVER `./gradlew`; JDK 21 toolchain preconfigured; Maven Central fast; Docker is running with `postgres:16-alpine` cached. Do NOT pull the Oracle image. Run `gradle <module>:spotlessApply` before each commit.

---

## File structure (created/modified by this plan)

```
gradle/libs.versions.toml                                  MODIFY: add jpa/flyway/testcontainers/driver aliases
starters/acme-test-support/
  build.gradle.kts                                         NEW
  src/main/java/com/acme/test/PostgresTestcontainersConfiguration.java   NEW
starters/acme-persistence-spring-boot-autoconfigure/
  build.gradle.kts                                         NEW
  src/main/java/com/acme/persistence/AuditedEntity.java    NEW
  src/main/java/com/acme/persistence/autoconfigure/PersistenceAutoConfiguration.java  NEW
  src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports  NEW
  src/test/java/com/acme/persistence/autoconfigure/ClockDateTimeProviderTest.java     NEW
starters/acme-persistence-spring-boot-starter/
  build.gradle.kts                                         NEW
examples/demo-service/
  build.gradle.kts                                         MODIFY: add persistence starter + test-support
  src/main/java/com/acme/demo/Order.java                   NEW
  src/main/java/com/acme/demo/OrderRepository.java         NEW
  src/main/java/com/acme/demo/OrderController.java         MODIFY: persist + lookup
  src/main/resources/application.yaml                      MODIFY: jpa + flyway config
  src/main/resources/db/migration/postgresql/V1__create_orders.sql   NEW
  src/main/resources/db/migration/oracle/V1__create_orders.sql       NEW (reference, not run here)
  src/test/java/com/acme/demo/OrderControllerIT.java       MODIFY: import Testcontainers config
  src/test/java/com/acme/demo/OrderPersistenceIT.java      NEW
settings.gradle.kts                                        MODIFY: include new modules
docs/decisions/0002-persistence-jpa-oracle-first.md        NEW
```

---

## Task 1: Version catalog additions

**Files:** Modify `gradle/libs.versions.toml`

- [ ] **Step 1: Append the new library aliases**

Add these entries under the existing `[libraries]` section of `gradle/libs.versions.toml` (keep existing entries unchanged; do not add version numbers — these are managed by the Spring Boot BOM applied via `acme-bom`):
```toml
spring-boot-starter-data-jpa = { module = "org.springframework.boot:spring-boot-starter-data-jpa" }
spring-boot-testcontainers = { module = "org.springframework.boot:spring-boot-testcontainers" }
flyway-core = { module = "org.flywaydb:flyway-core" }
flyway-database-postgresql = { module = "org.flywaydb:flyway-database-postgresql" }
flyway-database-oracle = { module = "org.flywaydb:flyway-database-oracle" }
postgresql = { module = "org.postgresql:postgresql" }
ojdbc11 = { module = "com.oracle.database.jdbc:ojdbc11" }
testcontainers-postgresql = { module = "org.testcontainers:postgresql" }
testcontainers-junit-jupiter = { module = "org.testcontainers:junit-jupiter" }
```

- [ ] **Step 2: Verify the catalog still parses**

Run: `gradle :platform:acme-bom:help -q`
Expected: BUILD SUCCESSFUL (no catalog parse error).

- [ ] **Step 3: Commit**
```bash
git add gradle/libs.versions.toml
git commit -m "build: add persistence/testcontainers library aliases to catalog"
```

---

## Task 2: `acme-test-support` module (Postgres Testcontainers config)

**Files:**
- Modify: `settings.gradle.kts`
- Create: `starters/acme-test-support/build.gradle.kts`
- Create: `starters/acme-test-support/src/main/java/com/acme/test/PostgresTestcontainersConfiguration.java`

- [ ] **Step 1: Register the module in settings**

In `settings.gradle.kts`, add `"starters:acme-test-support",` to the `include(...)` list (alongside the existing entries). The full `include(...)` block must become:
```kotlin
include(
    "platform:acme-bom",
    "starters:acme-test-support",
    "starters:acme-web-spring-boot-autoconfigure",
    "starters:acme-web-spring-boot-starter",
    "starters:acme-persistence-spring-boot-autoconfigure",
    "starters:acme-persistence-spring-boot-starter",
    "examples:demo-service",
)
```
> Note: this also pre-registers the two persistence modules created in Tasks 3–4. Their directories must exist before configuration — create them in Step 2.

- [ ] **Step 2: Create module directories**
```bash
mkdir -p starters/acme-test-support/src/main/java/com/acme/test \
  starters/acme-persistence-spring-boot-autoconfigure/src/main/java/com/acme/persistence/autoconfigure \
  starters/acme-persistence-spring-boot-autoconfigure/src/main/resources/META-INF/spring \
  starters/acme-persistence-spring-boot-autoconfigure/src/test/java/com/acme/persistence/autoconfigure \
  starters/acme-persistence-spring-boot-starter
```

- [ ] **Step 3: Module build script**

Create `starters/acme-test-support/build.gradle.kts`:
```kotlin
plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(libs.spring.boot.testcontainers)
    api(libs.testcontainers.postgresql)
    api(libs.testcontainers.junit.jupiter)
    api(libs.spring.boot.starter.test)
}
```
> These are `api` because consumers' test code uses Testcontainers + Spring Boot test types directly.

- [ ] **Step 4: Testcontainers configuration class**

Create `starters/acme-test-support/src/main/java/com/acme/test/PostgresTestcontainersConfiguration.java`:
```java
package com.acme.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Importable test configuration exposing a Postgres container wired to Spring Boot via
 * {@link ServiceConnection}. Integration tests {@code @Import} this to get a real database
 * with zero datasource configuration. Uses the locally-cached {@code postgres:16-alpine} image.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }
}
```

- [ ] **Step 5: Verify it compiles**

Run: `gradle :starters:acme-test-support:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Format + commit**
```bash
gradle :starters:acme-test-support:spotlessApply
git add settings.gradle.kts starters/acme-test-support
git commit -m "feat(test-support): Postgres Testcontainers @ServiceConnection config"
```

---

## Task 3: `acme-persistence` autoconfigure — auditing + clock (TDD)

**Files:**
- Create: `starters/acme-persistence-spring-boot-autoconfigure/build.gradle.kts`
- Create: `.../src/main/java/com/acme/persistence/AuditedEntity.java`
- Create: `.../src/main/java/com/acme/persistence/autoconfigure/PersistenceAutoConfiguration.java`
- Create: `.../src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `.../src/test/java/com/acme/persistence/autoconfigure/ClockDateTimeProviderTest.java`

- [ ] **Step 1: Module build script**

Create `starters/acme-persistence-spring-boot-autoconfigure/build.gradle.kts`:
```kotlin
plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(libs.spring.boot.autoconfigure)
    // JPA is required for the mapped superclass + auditing types; consumers always have it via the starter.
    api(libs.spring.boot.starter.data.jpa)

    annotationProcessor(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)

    testImplementation(libs.spring.boot.starter.test)
}
```

- [ ] **Step 2: Write the failing test for the clock-backed DateTimeProvider**

Create `.../src/test/java/com/acme/persistence/autoconfigure/ClockDateTimeProviderTest.java`:
```java
package com.acme.persistence.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.data.auditing.DateTimeProvider;

class ClockDateTimeProviderTest {

    @Test
    void dateTimeProviderUsesInjectedClock() {
        Instant fixed = Instant.parse("2026-06-17T12:00:00Z");
        Clock clock = Clock.fixed(fixed, ZoneOffset.UTC);

        PersistenceAutoConfiguration config = new PersistenceAutoConfiguration();
        DateTimeProvider provider = config.auditingDateTimeProvider(clock);

        TemporalAccessor now = provider.getNow().orElseThrow();
        assertThat(Instant.from(now)).isEqualTo(fixed);
    }
}
```

- [ ] **Step 3: Run the test — verify it FAILS**

Run: `gradle :starters:acme-persistence-spring-boot-autoconfigure:test --tests "*ClockDateTimeProviderTest"`
Expected: FAIL (compilation — `PersistenceAutoConfiguration` does not exist).

- [ ] **Step 4: `AuditedEntity` mapped superclass**

Create `.../src/main/java/com/acme/persistence/AuditedEntity.java`:
```java
package com.acme.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import java.time.Instant;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** Base entity providing optimistic-locking version + clock-backed created/updated audit timestamps. */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditedEntity {

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
```

- [ ] **Step 5: Auto-configuration with clock + DateTimeProvider + JPA auditing**

Create `.../src/main/java/com/acme/persistence/autoconfigure/PersistenceAutoConfiguration.java`:
```java
package com.acme.persistence.autoconfigure;

import java.time.Clock;
import java.util.Optional;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Enables JPA auditing backed by an injectable {@link Clock} so "now" is testable.
 * Both the clock and the date-time provider are overridable by the application.
 */
@AutoConfiguration
@ConditionalOnClass(AuditingEntityListener.class)
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class PersistenceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean(name = "auditingDateTimeProvider")
    @ConditionalOnMissingBean(name = "auditingDateTimeProvider")
    public DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(clock.instant());
    }
}
```

- [ ] **Step 6: Register for auto-configuration discovery**

Create `.../src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` with EXACTLY this single line:
```
com.acme.persistence.autoconfigure.PersistenceAutoConfiguration
```

- [ ] **Step 7: Run the test — verify it PASSES**

Run: `gradle :starters:acme-persistence-spring-boot-autoconfigure:test --tests "*ClockDateTimeProviderTest"`
Expected: PASS.

- [ ] **Step 8: Format + commit**
```bash
gradle :starters:acme-persistence-spring-boot-autoconfigure:spotlessApply
git add starters/acme-persistence-spring-boot-autoconfigure
git commit -m "feat(acme-persistence): clock-backed JPA auditing + AuditedEntity base"
```

---

## Task 4: `acme-persistence` thin starter

**Files:** Create `starters/acme-persistence-spring-boot-starter/build.gradle.kts`

- [ ] **Step 1: Starter build script (no code — aggregates JPA, Flyway, drivers)**

Create `starters/acme-persistence-spring-boot-starter/build.gradle.kts`:
```kotlin
plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(project(":starters:acme-persistence-spring-boot-autoconfigure"))
    api(libs.spring.boot.starter.data.jpa)
    api(libs.flyway.core)
    // Vendor-specific Flyway modules (Flyway 10+ requires the DB module on the classpath).
    api(libs.flyway.database.postgresql)
    api(libs.flyway.database.oracle)
    // JDBC drivers: Oracle is the primary/reference target; Postgres for the swappable path + tests.
    api(libs.ojdbc11)
    api(libs.postgresql)
}
```

- [ ] **Step 2: Verify it assembles**

Run: `gradle :starters:acme-persistence-spring-boot-starter:assemble`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**
```bash
git add starters/acme-persistence-spring-boot-starter/build.gradle.kts
git commit -m "feat(acme-persistence): thin starter aggregating JPA + Flyway + drivers"
```

---

## Task 5: demo-service — Order entity, repository, Flyway migrations, config

**Files:**
- Modify: `examples/demo-service/build.gradle.kts`
- Create: `examples/demo-service/src/main/java/com/acme/demo/Order.java`
- Create: `examples/demo-service/src/main/java/com/acme/demo/OrderRepository.java`
- Create: `examples/demo-service/src/main/resources/db/migration/postgresql/V1__create_orders.sql`
- Create: `examples/demo-service/src/main/resources/db/migration/oracle/V1__create_orders.sql`
- Modify: `examples/demo-service/src/main/resources/application.yaml`

- [ ] **Step 1: Add dependencies to demo build script**

Replace the `dependencies { ... }` block of `examples/demo-service/build.gradle.kts` with:
```kotlin
dependencies {
    implementation(platform(project(":platform:acme-bom")))
    implementation(project(":starters:acme-web-spring-boot-starter"))
    implementation(project(":starters:acme-persistence-spring-boot-starter"))
    testImplementation(project(":starters:acme-test-support"))
    testImplementation(libs.spring.boot.starter.test)
}
```
(The `plugins { ... }` block stays unchanged.)

- [ ] **Step 2: `Order` entity (SEQUENCE id, audited, optimistic-locked)**

Create `examples/demo-service/src/main/java/com/acme/demo/Order.java`:
```java
package com.acme.demo;

import com.acme.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

@Entity
@Table(name = "orders")
public class Order extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_seq")
    @SequenceGenerator(name = "order_seq", sequenceName = "order_seq", allocationSize = 50)
    private Long id;

    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    protected Order() {
        // for JPA
    }

    public Order(String sku, int quantity) {
        this.sku = sku;
        this.quantity = quantity;
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }
}
```

- [ ] **Step 3: Repository**

Create `examples/demo-service/src/main/java/com/acme/demo/OrderRepository.java`:
```java
package com.acme.demo;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {}
```

- [ ] **Step 4: Postgres migration (the one exercised by tests)**

Create `examples/demo-service/src/main/resources/db/migration/postgresql/V1__create_orders.sql`:
```sql
CREATE SEQUENCE order_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE orders (
    id         BIGINT       NOT NULL PRIMARY KEY,
    sku        VARCHAR(64)  NOT NULL,
    quantity   INTEGER      NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version    BIGINT       NOT NULL
);
```

- [ ] **Step 5: Oracle migration (reference; not run on this network)**

Create `examples/demo-service/src/main/resources/db/migration/oracle/V1__create_orders.sql`:
```sql
CREATE SEQUENCE order_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE orders (
    id         NUMBER(19)    NOT NULL PRIMARY KEY,
    sku        VARCHAR2(64)  NOT NULL,
    quantity   NUMBER(10)    NOT NULL,
    created_at TIMESTAMP(6)  NOT NULL,
    updated_at TIMESTAMP(6)  NOT NULL,
    version    NUMBER(19)    NOT NULL
);
```

- [ ] **Step 6: JPA + Flyway config**

Replace `examples/demo-service/src/main/resources/application.yaml` with:
```yaml
spring:
  application:
    name: demo-service
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    locations: classpath:db/migration/{vendor}
server:
  port: 8080
```
> `ddl-auto: validate` makes Hibernate verify the entity mapping against the Flyway-created schema at startup — so a mismatch fails fast. `{vendor}` resolves to `postgresql` (tests) or `oracle` (reference) automatically.

- [ ] **Step 7: Verify compilation**

Run: `gradle :examples:demo-service:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**
```bash
gradle :examples:demo-service:spotlessApply
git add examples/demo-service
git commit -m "feat(demo): Order JPA entity, repository, Flyway migrations, JPA config"
```

---

## Task 6: demo-service — controller persists + looks up; keep web IT green

**Files:**
- Modify: `examples/demo-service/src/main/java/com/acme/demo/OrderController.java`
- Modify: `examples/demo-service/src/test/java/com/acme/demo/OrderControllerIT.java`

- [ ] **Step 1: Rewrite the controller to use the repository**

Replace the entire contents of `examples/demo-service/src/main/java/com/acme/demo/OrderController.java` with:
```java
package com.acme.demo;

import com.acme.web.error.ApiException;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/orders")
public class OrderController {

    private final OrderRepository orders;

    public OrderController(OrderRepository orders) {
        this.orders = orders;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody CreateOrderRequest req) {
        Order saved = orders.save(new Order(req.sku(), req.quantity()));
        return Map.of("id", saved.getId(), "sku", saved.getSku(), "quantity", saved.getQuantity());
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        Order order = orders.findById(id)
                .orElseThrow(() -> new ApiException(DemoErrorCode.ORDER_NOT_FOUND, Map.of("orderId", id)));
        return Map.of("id", order.getId(), "sku", order.getSku(), "quantity", order.getQuantity());
    }
}
```
> `id` is now `Long`. The path `/v1/orders/42` still maps (42 parses to Long) and returns 404 when absent — keeping the existing web test valid.

- [ ] **Step 2: Make the existing web IT start its context against Testcontainers**

The demo now requires a datasource, so `@SpringBootTest` needs a database. Replace the entire contents of `examples/demo-service/src/test/java/com/acme/demo/OrderControllerIT.java` with:
```java
package com.acme.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.test.PostgresTestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfiguration.class)
class OrderControllerIT {

    @Autowired
    MockMvc mvc;

    @Test
    void returnsProblemJsonForMissingOrder() throws Exception {
        mvc.perform(get("/v1/orders/999999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.title").value("Order not found"))
                .andExpect(jsonPath("$.params.orderId").value(999999));
    }

    @Test
    void returnsValidationProblemForBadBody() throws Exception {
        mvc.perform(post("/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"\",\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors").isArray());
    }
}
```
> The missing-order id changed to `999999` (a value guaranteed absent) and `params.orderId` is now asserted as a JSON number (the path variable is a `Long`).

- [ ] **Step 3: Verify both web tests still pass (now against a real DB)**

Run: `gradle :examples:demo-service:test --tests "*OrderControllerIT"`
Expected: PASS (Testcontainers starts `postgres:16-alpine` from the local cache; Flyway runs V1; context starts; both assertions green).

- [ ] **Step 4: Commit**
```bash
gradle :examples:demo-service:spotlessApply
git add examples/demo-service
git commit -m "feat(demo): persist+lookup orders via repository; web IT on Testcontainers"
```

---

## Task 7: demo-service — persistence integration test (the proof)

**Files:** Create `examples/demo-service/src/test/java/com/acme/demo/OrderPersistenceIT.java`

- [ ] **Step 1: Write the persistence integration test**

Create `examples/demo-service/src/test/java/com/acme/demo/OrderPersistenceIT.java`:
```java
package com.acme.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.test.PostgresTestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(PostgresTestcontainersConfiguration.class)
class OrderPersistenceIT {

    @Autowired
    OrderRepository orders;

    @Test
    void persistsAndRetrievesOrderWithAuditingAndVersion() {
        Order saved = orders.save(new Order("SKU-1", 3));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getVersion()).isZero();

        Order found = orders.findById(saved.getId()).orElseThrow();
        assertThat(found.getSku()).isEqualTo("SKU-1");
        assertThat(found.getQuantity()).isEqualTo(3);
        assertThat(found.getCreatedAt()).isEqualTo(saved.getCreatedAt());
    }
}
```

- [ ] **Step 2: Run it — this proves JPA + Flyway + auditing + sequence id all work end-to-end**

Run: `gradle :examples:demo-service:test --tests "*OrderPersistenceIT"`
Expected: PASS. A green test proves: the `acme-persistence` starter auto-configured JPA auditing (created/updated set via the clock-backed provider), the `@Version` defaulted to 0, the `SEQUENCE` id generator produced an id, and the Flyway `{vendor}` migration created the schema that Hibernate validated.

- [ ] **Step 3: Commit**
```bash
git add examples/demo-service/src/test/java/com/acme/demo/OrderPersistenceIT.java
git commit -m "test(demo): persistence IT proving JPA auditing + Flyway + sequence id"
```

---

## Task 8: Full build green + persistence ADR

**Files:** Create `docs/decisions/0002-persistence-jpa-oracle-first.md`

- [ ] **Step 1: Run the whole build**

Run: `gradle build`
Expected: BUILD SUCCESSFUL — all modules compile, Spotless passes, all tests pass (web IT + persistence IT on Testcontainers).
> If `spotlessCheck` fails, run `gradle spotlessApply` and re-run. If a test fails for a real reason, debug; if stuck, report BLOCKED.

- [ ] **Step 2: Record the persistence decision**

Create `docs/decisions/0002-persistence-jpa-oracle-first.md`:
```markdown
---
status: accepted
date: 2026-06-17
---

# Persistence: Spring Data JPA, Oracle-first but DB-agnostic

## Context and Problem Statement

The boilerplate needs a reusable persistence layer. The target infrastructure is Oracle, but
the reusable layer must not be coupled to a specific database, and local/CI verification needs a
fast, reliable container (the Oracle Free image is large and not always reachable).

## Decision Outcome

- Spring Data JPA / Hibernate via the `acme-persistence` starter; `AuditedEntity` provides
  clock-backed `@CreatedDate`/`@LastModifiedDate` and an optimistic-locking `@Version`.
- IDs use `GenerationType.SEQUENCE` (portable; not `IDENTITY`). Identifiers kept short for Oracle.
- Flyway owns all DDL via `classpath:db/migration/{vendor}`; Oracle is the reference vendor dir,
  Postgres the swappable path.
- Integration tests run on Postgres Testcontainers (`postgres:16-alpine`, locally cached).
  Oracle verification is a deferred opt-in profile — the Oracle Free Testcontainers image is not
  pulled in this environment.
- Switching the runtime DB = profile + JDBC driver + Hibernate dialect; no code changes.

Full detail: `docs/superpowers/specs/2026-06-17-acme-boilerplate-design.md` §5 (acme-persistence).
```

- [ ] **Step 3: Commit**
```bash
git add docs/decisions/0002-persistence-jpa-oracle-first.md
git commit -m "docs: ADR 0002 persistence JPA Oracle-first DB-agnostic"
```

---

## Done criteria for SP-1

- `gradle build` green (all modules, Spotless, all tests).
- `acme-persistence` starter auto-configures JPA auditing (clock-backed) + provides `AuditedEntity`; overridable.
- `demo-service` persists and retrieves `Order` against a real database via Testcontainers; `OrderPersistenceIT` proves auditing timestamps, `@Version`, sequence id, and Flyway `{vendor}` migration.
- Existing web `OrderControllerIT` still green (now context-started against Testcontainers).
- Oracle is the documented reference (driver shipped, `oracle` migration dir present); Oracle Testcontainers verification deferred.

---

## Self-review notes

- **Spec coverage (§5 acme-persistence):** JPA base ✓ (Task 3), `@EnableJpaAuditing` clock-backed `DateTimeProvider` ✓ (Task 3), Flyway vendor dirs ✓ (Task 5), optimistic locking `@Version` ✓ (AuditedEntity), `GenerationType.SEQUENCE` + short identifiers + CLOB-ready types ✓ (Tasks 3/5), Testcontainers base ✓ (Task 2, acme-test-support). HikariCP is the Spring Boot default datasource pool (no extra config needed). Deferred (documented): Oracle Testcontainers run; UCP option.
- **Type consistency:** `AuditedEntity.getCreatedAt/getUpdatedAt/getVersion` used in `OrderPersistenceIT`; `OrderRepository extends JpaRepository<Order, Long>`; controller and tests use `Long` id consistently; `auditingDateTimeProvider` bean name matches `dateTimeProviderRef`.
- **No placeholders:** all steps contain concrete code/commands.
- **Behavior-change note:** demo POST now returns 201 (was 202) and persists; GET looks up. Existing web IT updated in Task 6 (missing id → 999999, numeric `params.orderId`, content-type asserted). No other module affected.
- **Network caveat surfaced:** Oracle image not pulled; tests use cached Postgres image — explicitly documented in ADR 0002 and the plan header.
