# SP-2: Observability Starter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Add a reusable `acme-observability` Spring Boot starter bundling Actuator + Micrometer Tracing (OTel bridge → OTLP) + Prometheus, liveness/readiness health groups, graceful shutdown, and ShedLock (JDBC, `usingDbTime()`) scheduled-job dedup — and prove the wiring in `demo-service`.

**Architecture:** The observability autoconfigure module ships (a) a validated `@ConfigurationProperties` (`acme.observability`) for fail-fast config, and (b) a ShedLock auto-configuration that builds a `usingDbTime()` JDBC `LockProvider` from the app `DataSource` and enables `@SchedulerLock`. Actuator, Micrometer tracing, and the OTLP/Prometheus registries come transitively from the thin starter; the app turns on probes/graceful-shutdown via `application.yaml`. Verification is context-based (beans present, endpoints respond, a real JDBC lock is acquired against Testcontainers Postgres) — no external collector is required.

**Tech Stack:** Java 21, Spring Boot 3.5.6, Spring Boot Actuator, Micrometer Tracing (OTel bridge), OpenTelemetry OTLP exporter, ShedLock 7.7.0, Testcontainers Postgres, JUnit 5 + AssertJ.

> Spec: `docs/superpowers/specs/2026-06-17-acme-boilerplate-design.md` §5 (acme-observability). Builds on SP-0/SP-1.
> **Environment (every task):** work from `/Users/npden4ik/Projects/java-boilerplate`; system `gradle` (8.14) NEVER `./gradlew`; JDK 21 toolchain preconfigured; Maven Central fast; Docker up, `postgres:16-alpine` cached. `gradle <module>:spotlessApply` before each commit.

---

## File structure

```
gradle/libs.versions.toml                          MODIFY: actuator/micrometer/otlp/shedlock aliases
starters/acme-observability-spring-boot-autoconfigure/
  build.gradle.kts                                 NEW
  src/main/java/com/acme/observability/ObservabilityProperties.java          NEW
  src/main/java/com/acme/observability/autoconfigure/SchedulerLockAutoConfiguration.java  NEW
  src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports  NEW
  src/test/java/com/acme/observability/ObservabilityPropertiesTest.java       NEW
starters/acme-observability-spring-boot-starter/
  build.gradle.kts                                 NEW
examples/demo-service/
  build.gradle.kts                                 MODIFY: add observability starter
  src/main/java/com/acme/demo/HeartbeatJob.java    NEW (@Scheduled @SchedulerLock)
  src/main/resources/application.yaml              MODIFY: actuator probes/groups, graceful shutdown, scheduling
  src/main/resources/db/migration/postgresql/V2__shedlock.sql   NEW
  src/main/resources/db/migration/oracle/V2__shedlock.sql       NEW (reference)
  src/test/java/com/acme/demo/ActuatorHealthIT.java   NEW
  src/test/java/com/acme/demo/SchedulerLockIT.java     NEW
settings.gradle.kts                                MODIFY: include observability modules
docs/decisions/0003-observability-otel-shedlock.md  NEW
```

---

## Task 1: Version catalog additions

Modify `gradle/libs.versions.toml`. Add to `[versions]`:
```toml
shedlock = "7.7.0"
```
Add to `[libraries]` (unversioned = Boot BOM managed, except shedlock):
```toml
spring-boot-starter-actuator = { module = "org.springframework.boot:spring-boot-starter-actuator" }
spring-boot-starter-jdbc = { module = "org.springframework.boot:spring-boot-starter-jdbc" }
micrometer-tracing-bridge-otel = { module = "io.micrometer:micrometer-tracing-bridge-otel" }
opentelemetry-exporter-otlp = { module = "io.opentelemetry:opentelemetry-exporter-otlp" }
micrometer-registry-otlp = { module = "io.micrometer:micrometer-registry-otlp" }
micrometer-registry-prometheus = { module = "io.micrometer:micrometer-registry-prometheus" }
shedlock-spring = { module = "net.javacrumbs.shedlock:shedlock-spring", version.ref = "shedlock" }
shedlock-provider-jdbc-template = { module = "net.javacrumbs.shedlock:shedlock-provider-jdbc-template", version.ref = "shedlock" }
```

- [ ] **Step 1:** Apply the edits above (keep existing entries).
- [ ] **Step 2:** Verify: `gradle :platform:acme-bom:help -q` → BUILD SUCCESSFUL.
- [ ] **Step 3:** Commit:
```bash
git add gradle/libs.versions.toml
git commit -m "build: add observability/shedlock library aliases to catalog"
```

---

## Task 2: acme-observability autoconfigure — properties (TDD) + ShedLock

**Files:**
- Modify: `settings.gradle.kts`
- Create: module `build.gradle.kts`, `ObservabilityProperties.java`, `SchedulerLockAutoConfiguration.java`, `AutoConfiguration.imports`, `ObservabilityPropertiesTest.java`

- [ ] **Step 1: Register modules in settings**

In `settings.gradle.kts`, replace the `include(...)` block so it reads exactly:
```kotlin
include(
    "platform:acme-bom",
    "starters:acme-test-support",
    "starters:acme-web-spring-boot-autoconfigure",
    "starters:acme-web-spring-boot-starter",
    "starters:acme-persistence-spring-boot-autoconfigure",
    "starters:acme-persistence-spring-boot-starter",
    "starters:acme-observability-spring-boot-autoconfigure",
    "starters:acme-observability-spring-boot-starter",
    "examples:demo-service",
)
```

- [ ] **Step 2: Create directories**
```bash
mkdir -p starters/acme-observability-spring-boot-autoconfigure/src/main/java/com/acme/observability/autoconfigure \
  starters/acme-observability-spring-boot-autoconfigure/src/main/resources/META-INF/spring \
  starters/acme-observability-spring-boot-autoconfigure/src/test/java/com/acme/observability \
  starters/acme-observability-spring-boot-starter
```

- [ ] **Step 3: Module build script**

Create `starters/acme-observability-spring-boot-autoconfigure/build.gradle.kts`:
```kotlin
plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(libs.spring.boot.autoconfigure)
    api(libs.spring.boot.starter.jdbc)
    api(libs.shedlock.spring)
    api(libs.shedlock.provider.jdbc.template)

    annotationProcessor(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)

    testImplementation(libs.spring.boot.starter.test)
}
```

- [ ] **Step 4: Write the failing properties test**

Create `.../src/test/java/com/acme/observability/ObservabilityPropertiesTest.java`:
```java
package com.acme.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ObservabilityPropertiesTest {

    @Test
    void defaultsAreSensible() {
        ObservabilityProperties props = new ObservabilityProperties();
        assertThat(props.getSchedulerLock().getDefaultLockAtMostFor()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void valuesBind() {
        ObservabilityProperties props = new ObservabilityProperties();
        props.getSchedulerLock().setDefaultLockAtMostFor(Duration.ofMinutes(2));
        assertThat(props.getSchedulerLock().getDefaultLockAtMostFor()).isEqualTo(Duration.ofMinutes(2));
    }
}
```

- [ ] **Step 5: Run, verify FAIL:** `gradle :starters:acme-observability-spring-boot-autoconfigure:test --tests "*ObservabilityPropertiesTest"` → FAIL (class missing).

- [ ] **Step 6: Properties class**

Create `.../src/main/java/com/acme/observability/ObservabilityProperties.java`:
```java
package com.acme.observability;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Validated configuration for the observability starter. Fails fast on invalid values. */
@Validated
@ConfigurationProperties(prefix = "acme.observability")
public class ObservabilityProperties {

    @NotNull
    private final SchedulerLock schedulerLock = new SchedulerLock();

    public SchedulerLock getSchedulerLock() {
        return schedulerLock;
    }

    /** ShedLock defaults. */
    public static class SchedulerLock {

        /** Upper bound a lock is held if the holding node dies mid-job. */
        @NotNull
        private Duration defaultLockAtMostFor = Duration.ofMinutes(10);

        public Duration getDefaultLockAtMostFor() {
            return defaultLockAtMostFor;
        }

        public void setDefaultLockAtMostFor(Duration defaultLockAtMostFor) {
            this.defaultLockAtMostFor = defaultLockAtMostFor;
        }
    }
}
```

- [ ] **Step 7: ShedLock auto-configuration**

Create `.../src/main/java/com/acme/observability/autoconfigure/SchedulerLockAutoConfiguration.java`:
```java
package com.acme.observability.autoconfigure;

import com.acme.observability.ObservabilityProperties;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Configures a DB-time JDBC {@link LockProvider} and enables {@link net.javacrumbs.shedlock.spring.annotation.SchedulerLock}
 * so {@code @Scheduled} jobs run on exactly one instance. {@code usingDbTime()} makes locking
 * resilient to clock skew across nodes (DB-portable: Postgres + Oracle).
 */
@AutoConfiguration
@ConditionalOnClass(LockProvider.class)
@ConditionalOnBean(DataSource.class)
@EnableConfigurationProperties(ObservabilityProperties.class)
@EnableSchedulerLock(defaultLockAtMostFor = "${acme.observability.scheduler-lock.default-lock-at-most-for:PT10M}")
public class SchedulerLockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
```

- [ ] **Step 8: Registration file**

Create `.../src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` with EXACTLY:
```
com.acme.observability.autoconfigure.SchedulerLockAutoConfiguration
```

- [ ] **Step 9: Run, verify PASS:** `gradle :starters:acme-observability-spring-boot-autoconfigure:test --tests "*ObservabilityPropertiesTest"` → PASS. Also `gradle :starters:acme-observability-spring-boot-autoconfigure:compileJava` → BUILD SUCCESSFUL.

- [ ] **Step 10: Format + commit**
```bash
gradle :starters:acme-observability-spring-boot-autoconfigure:spotlessApply
git add settings.gradle.kts starters/acme-observability-spring-boot-autoconfigure
git commit -m "feat(acme-observability): validated props + ShedLock JDBC (usingDbTime) auto-config"
```

---

## Task 3: acme-observability thin starter

Create `starters/acme-observability-spring-boot-starter/build.gradle.kts`:
```kotlin
plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(project(":starters:acme-observability-spring-boot-autoconfigure"))
    api(libs.spring.boot.starter.actuator)
    api(libs.micrometer.tracing.bridge.otel)
    api(libs.opentelemetry.exporter.otlp)
    api(libs.micrometer.registry.otlp)
    api(libs.micrometer.registry.prometheus)
}
```
- [ ] **Step 1:** Create the file.
- [ ] **Step 2:** Verify: `gradle :starters:acme-observability-spring-boot-starter:assemble` → BUILD SUCCESSFUL.
- [ ] **Step 3:** Commit:
```bash
git add starters/acme-observability-spring-boot-starter/build.gradle.kts
git commit -m "feat(acme-observability): thin starter (actuator + tracing + otlp + prometheus)"
```

---

## Task 4: demo-service — actuator config, heartbeat job, shedlock migration

**Files:**
- Modify: `examples/demo-service/build.gradle.kts`, `application.yaml`
- Create: `HeartbeatJob.java`, `db/migration/{postgresql,oracle}/V2__shedlock.sql`

- [ ] **Step 1: Add the observability starter to demo**

In `examples/demo-service/build.gradle.kts`, add to the `dependencies { }` block (after the persistence starter line):
```kotlin
    implementation(project(":starters:acme-observability-spring-boot-starter"))
```

- [ ] **Step 2: Heartbeat job (scheduled, lock-guarded)**

Create `examples/demo-service/src/main/java/com/acme/demo/HeartbeatJob.java`:
```java
package com.acme.demo;

import java.util.concurrent.atomic.AtomicInteger;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Demo scheduled job whose runs are deduped across instances by ShedLock. */
@Component
public class HeartbeatJob {

    private final AtomicInteger runs = new AtomicInteger();

    @Scheduled(fixedRate = 200)
    @SchedulerLock(name = "heartbeat", lockAtMostFor = "PT30S", lockAtLeastFor = "PT0S")
    public void beat() {
        runs.incrementAndGet();
    }

    public int runs() {
        return runs.get();
    }
}
```

- [ ] **Step 3: Postgres shedlock migration**

Create `examples/demo-service/src/main/resources/db/migration/postgresql/V2__shedlock.sql`:
```sql
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
```

- [ ] **Step 4: Oracle shedlock migration (reference)**

Create `examples/demo-service/src/main/resources/db/migration/oracle/V2__shedlock.sql`:
```sql
CREATE TABLE shedlock (
    name       VARCHAR2(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP(3)  NOT NULL,
    locked_at  TIMESTAMP(3)  NOT NULL,
    locked_by  VARCHAR2(255) NOT NULL
);
```

- [ ] **Step 5: Actuator + scheduling config**

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
  shutdown: graceful
management:
  endpoint:
    health:
      probes:
        enabled: true
      group:
        readiness:
          include: readinessState,db
        liveness:
          include: livenessState
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
```
> `ddl-auto: validate` ignores the `shedlock` table (no entity maps it) — that is fine; Hibernate only validates mapped entities.

- [ ] **Step 6: Verify compile:** `gradle :examples:demo-service:compileJava` → BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**
```bash
gradle :examples:demo-service:spotlessApply
git add examples/demo-service
git commit -m "feat(demo): actuator probes, graceful shutdown, ShedLock heartbeat job"
```

---

## Task 5: Integration tests — health endpoints + ShedLock + tracing beans

**Files:** Create `ActuatorHealthIT.java`, `SchedulerLockIT.java`

- [ ] **Step 1: Actuator health IT**

Create `examples/demo-service/src/test/java/com/acme/demo/ActuatorHealthIT.java`:
```java
package com.acme.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.test.PostgresTestcontainersConfiguration;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "management.tracing.sampling.probability=0.0")
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfiguration.class)
class ActuatorHealthIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext ctx;

    @Test
    void livenessProbeIsUp() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void readinessProbeIsUp() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void tracerBeanIsConfigured() {
        // Micrometer Tracing OTel bridge wired by the observability starter.
        org.assertj.core.api.Assertions.assertThat(ctx.getBean(Tracer.class)).isNotNull();
    }
}
```

- [ ] **Step 2: ShedLock IT (proves DB-time JDBC lock acquisition end-to-end)**

Create `examples/demo-service/src/test/java/com/acme/demo/SchedulerLockIT.java`:
```java
package com.acme.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.test.PostgresTestcontainersConfiguration;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(PostgresTestcontainersConfiguration.class)
class SchedulerLockIT {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    HeartbeatJob heartbeat;

    @Test
    void scheduledJobRunsAndShedLockRowIsWritten() {
        // The @Scheduled @SchedulerLock job runs every 200ms; wait until it has executed.
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(heartbeat.runs()).isPositive());

        Long lockRows = jdbc.queryForObject(
                "SELECT count(*) FROM shedlock WHERE name = 'heartbeat'", Long.class);
        assertThat(lockRows).isEqualTo(1L);
    }
}
```

- [ ] **Step 3: Run the ITs**

Run: `gradle :examples:demo-service:test --tests "*ActuatorHealthIT" --tests "*SchedulerLockIT"`
Expected: PASS. Green proves: Actuator liveness/readiness groups respond UP, the Micrometer `Tracer` bean is wired by the starter, and ShedLock acquired a real DB-time JDBC lock (a `shedlock` row for `heartbeat`) — the scheduled job is dedup-guarded.

- [ ] **Step 4: Commit**
```bash
gradle :examples:demo-service:spotlessApply
git add examples/demo-service/src/test
git commit -m "test(demo): actuator health + tracer bean + ShedLock acquisition ITs"
```

---

## Task 6: Full build green + ADR

- [ ] **Step 1:** `gradle build` → BUILD SUCCESSFUL (all modules, Spotless, all tests incl. new ITs). Fix Spotless via `spotlessApply` if needed; debug real failures, BLOCKED if stuck.
- [ ] **Step 2:** Create `docs/decisions/0003-observability-otel-shedlock.md`:
```markdown
---
status: accepted
date: 2026-06-17
---

# Observability: Micrometer OTel bridge + Actuator probes + ShedLock

## Context and Problem Statement

Services need metrics, tracing, health probes, and safe scheduled jobs across replicas, with a
DB-portable locking story (Oracle-first, Postgres swappable).

## Decision Outcome

- Tracing via Micrometer Tracing OTel bridge → OTLP (CNCF-standard wire format), metrics via
  Micrometer with OTLP + Prometheus registries, all behind Spring Boot Actuator.
- Liveness probe is minimal (no external deps → no restart loops); readiness includes the DB.
- Graceful shutdown enabled (`server.shutdown=graceful`).
- Scheduled-job dedup via ShedLock JDBC provider with `usingDbTime()` — clock-skew safe and
  portable across Postgres and Oracle; no extra infrastructure.
- Config is validated (`@Validated @ConfigurationProperties`) for fail-fast startup.

Full detail: `docs/superpowers/specs/2026-06-17-acme-boilerplate-design.md` §5 (acme-observability).
```
- [ ] **Step 3:** Commit:
```bash
git add docs/decisions/0003-observability-otel-shedlock.md
git commit -m "docs: ADR 0003 observability OTel + Actuator + ShedLock"
```

---

## Done criteria for SP-2

- `gradle build` green (all modules, Spotless, all tests).
- `acme-observability` starter ships Actuator + Micrometer tracing (OTel bridge) + OTLP/Prometheus + ShedLock; validated props.
- demo exposes liveness/readiness probes (UP), graceful shutdown, and a ShedLock-guarded scheduled job.
- ITs prove: health probes UP, `Tracer` bean wired, real DB-time ShedLock row acquired.

---

## Self-review notes

- **Spec coverage (§5 acme-observability):** Micrometer+tracing OTel bridge ✓, OTLP+Prometheus ✓, Actuator health liveness/readiness ✓ (liveness minimal), graceful shutdown ✓, ShedLock `usingDbTime()` ✓, config fail-fast `@Validated` ✓. Native structured logging + Clock bean: Clock already ships in `acme-persistence` (SP-1); native structured logging is a property-only concern documented in the spec, deferred (no code). OTLP export endpoint is config-driven (off by default in tests via sampling 0).
- **Type consistency:** `ObservabilityProperties.getSchedulerLock().getDefaultLockAtMostFor()` used in test; `HeartbeatJob.runs()` used in `SchedulerLockIT`; `@SchedulerLock(name="heartbeat")` matches the SQL `WHERE name='heartbeat'`.
- **No placeholders.** All code/commands concrete.
- **Verification reality:** ShedLock IT asserts a real lock row in Testcontainers Postgres (the meaningful proof). Tracing verified by bean presence (no collector needed). `management.tracing.sampling.probability=0.0` in the health IT avoids any export attempts.
