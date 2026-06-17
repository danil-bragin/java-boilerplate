# BANK-0.5: acme-bank skeleton + Avro contracts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Stand up the `examples/acme-bank` multi-module skeleton, the shared `bank-contracts` module (Avro integration-event schemas + codegen, with `Money` as a `{amount,asset}` string record and a `MoneyMapper` bridge to `acme-money`), a reusable `acme.bank-service-conventions` build plugin (java conventions + jMolecules + ArchUnit), and the `compose.bank.yaml` local stack — so BANK-1..5 services can plug in.

**Architecture:** `examples/acme-bank/bank-contracts` is a Gradle module applying the davidmc24 Avro plugin; `.avsc` schemas codegen `SpecificRecord` types shared by all services; a `MoneyMapper` converts `com.acme.money.Money ↔ Avro Money(amount:string, asset:string)` (no float on the wire). A `build-logic` convention plugin `acme.bank-service-conventions` bundles the java conventions plus jMolecules (DDD stereotypes) and ArchUnit (test deps) so each BANK service applies one plugin to get the template's architecture toolchain. Remaining event schemas are added per service in BANK-1..5 as they are introduced.

**Tech Stack:** Java 21, Gradle 8.14, Apache Avro 1.11.4 + davidmc24 plugin, Confluent serde (via `acme-avro`), jMolecules 2025.0.2, ArchUnit 1.4.2, `acme-money`.

> Spec: `docs/superpowers/specs/2026-06-18-acme-bank-design.md` §4/§5/§8. Builds on BANK-0 (`acme-money`).
> **Environment (every task):** work from `/Users/npden4ik/Projects/java-boilerplate`; system `gradle` (8.14) NEVER `./gradlew`; JDK 21 toolchain; Maven Central fast; Confluent repo reachable (slow); Docker up (Postgres/Redpanda cached). `gradle <module>:spotlessApply` before each commit.

---

## File structure

```
gradle/libs.versions.toml                          MODIFY: jmolecules + archunit aliases
settings.gradle.kts                                MODIFY: include examples:acme-bank:bank-contracts
build-logic/src/main/kotlin/acme.bank-service-conventions.gradle.kts   NEW
examples/acme-bank/
  bank-contracts/
    build.gradle.kts                               NEW (avro plugin + acme-avro + acme-money)
    src/main/avro/Money.avsc                       NEW (shared {amount,asset} record)
    src/main/avro/TransferRequested.avsc           NEW
    src/main/avro/TransferCompleted.avsc           NEW
    src/main/java/com/acme/bank/contracts/MoneyMapper.java   NEW (Money ↔ Avro Money)
    src/test/java/com/acme/bank/contracts/MoneyMapperTest.java   NEW
  compose.bank.yaml                                NEW (Postgres, Redpanda+SR, Keycloak, otel-lgtm)
```

---

## Task 1: Catalog (jMolecules + ArchUnit)

- [ ] **Step 1:** In `gradle/libs.versions.toml` add to `[versions]`:
```toml
jmolecules = "2025.0.2"
archunit = "1.4.2"
```
and to `[libraries]`:
```toml
jmolecules-bom = { module = "org.jmolecules:jmolecules-bom", version.ref = "jmolecules" }
jmolecules-ddd = { module = "org.jmolecules:jmolecules-ddd" }
jmolecules-events = { module = "org.jmolecules:jmolecules-events" }
jmolecules-archunit = { module = "org.jmolecules.integrations:jmolecules-archunit" }
archunit-junit5 = { module = "com.tngtech.archunit:archunit-junit5", version.ref = "archunit" }
```
- [ ] **Step 2:** Verify `gradle :platform:acme-bom:help -q` → BUILD SUCCESSFUL.
- [ ] **Step 3:** Commit:
```bash
git add gradle/libs.versions.toml
git commit -m "build: add jMolecules + ArchUnit aliases for the bank example"
```

---

## Task 2: `acme-bank` settings + `bank-contracts` module with Avro codegen

**Files:** `settings.gradle.kts` (modify), `examples/acme-bank/bank-contracts/build.gradle.kts`, three `.avsc` files.

- [ ] **Step 1: settings** — add to the `include(...)` list:
```kotlin
    "examples:acme-bank:bank-contracts",
```
- [ ] **Step 2: dirs**
```bash
mkdir -p examples/acme-bank/bank-contracts/src/main/avro \
  examples/acme-bank/bank-contracts/src/main/java/com/acme/bank/contracts \
  examples/acme-bank/bank-contracts/src/test/java/com/acme/bank/contracts
```
- [ ] **Step 3: build script** — `examples/acme-bank/bank-contracts/build.gradle.kts`:
```kotlin
plugins {
    id("acme.java-conventions")
    alias(libs.plugins.avro)
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(project(":starters:acme-money"))
    api(project(":starters:acme-avro-spring-boot-starter")) // Avro + Confluent serde for consumers
    testImplementation(libs.spring.boot.starter.test)
}

// Generated Avro sources are not subject to Spotless style.
spotless {
    java {
        targetExclude("build/generated-main-avro-java/**")
    }
}
```
- [ ] **Step 4: shared Money Avro record** — `examples/acme-bank/bank-contracts/src/main/avro/Money.avsc`:
```json
{
  "namespace": "com.acme.bank.contracts.avro",
  "type": "record",
  "name": "Money",
  "fields": [
    { "name": "amount", "type": "string" },
    { "name": "asset", "type": "string" }
  ]
}
```
- [ ] **Step 5: `TransferRequested`** — `examples/acme-bank/bank-contracts/src/main/avro/TransferRequested.avsc` (references the Money type by full name):
```json
{
  "namespace": "com.acme.bank.contracts.avro",
  "type": "record",
  "name": "TransferRequested",
  "fields": [
    { "name": "transferId", "type": "string" },
    { "name": "sourceAccountId", "type": "string" },
    { "name": "destinationAccountId", "type": "string" },
    { "name": "amount", "type": "com.acme.bank.contracts.avro.Money" },
    { "name": "requestedBy", "type": "string" },
    { "name": "requestedAt", "type": { "type": "long", "logicalType": "timestamp-millis" } }
  ]
}
```
- [ ] **Step 6: `TransferCompleted`** — `examples/acme-bank/bank-contracts/src/main/avro/TransferCompleted.avsc`:
```json
{
  "namespace": "com.acme.bank.contracts.avro",
  "type": "record",
  "name": "TransferCompleted",
  "fields": [
    { "name": "transferId", "type": "string" },
    { "name": "postingId", "type": "string" },
    { "name": "completedAt", "type": { "type": "long", "logicalType": "timestamp-millis" } }
  ]
}
```
- [ ] **Step 7: verify codegen + compile** — `gradle :examples:acme-bank:bank-contracts:compileJava`.
> The davidmc24 Avro plugin resolves the cross-file `Money` reference within the `src/main/avro` source set and generates `com.acme.bank.contracts.avro.{Money,TransferRequested,TransferCompleted}` into `build/generated-main-avro-java/`. Expected: BUILD SUCCESSFUL. If the cross-schema reference is not resolved, configure the plugin to process schemas with dependency resolution (the plugin's `generateAvroJava` handles referenced types when they are in the same source set; if needed, ensure `Money.avsc` sorts/compiles first — the plugin does topological ordering by default). Report any plugin config you had to add.
- [ ] **Step 8: commit**
```bash
git add settings.gradle.kts examples/acme-bank/bank-contracts/build.gradle.kts examples/acme-bank/bank-contracts/src/main/avro
git commit -m "feat(bank-contracts): Avro schemas (Money + TransferRequested/Completed) + codegen"
```

---

## Task 3: `MoneyMapper` (Money ↔ Avro) (TDD)

**Files:** `MoneyMapper.java`, test `MoneyMapperTest.java`.

- [ ] **Step 1: failing test** — `examples/acme-bank/bank-contracts/src/test/java/com/acme/bank/contracts/MoneyMapperTest.java`:
```java
package com.acme.bank.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.money.Assets;
import com.acme.money.Money;
import org.junit.jupiter.api.Test;

class MoneyMapperTest {

    @Test
    void roundTripsThroughAvro() {
        Money original = Money.of("1234.56", Assets.USD);

        com.acme.bank.contracts.avro.Money avro = MoneyMapper.toAvro(original);
        assertThat(avro.getAmount().toString()).isEqualTo("1234.56");
        assertThat(avro.getAsset().toString()).isEqualTo("USD");

        Money back = MoneyMapper.fromAvro(avro);
        assertThat(back).isEqualTo(original);
    }

    @Test
    void preservesCryptoPrecision() {
        Money wei = Money.of("0.000000000000000001", Assets.ETH);
        assertThat(MoneyMapper.fromAvro(MoneyMapper.toAvro(wei))).isEqualTo(wei);
    }
}
```
- [ ] **Step 2: run, FAIL** — `gradle :examples:acme-bank:bank-contracts:test --tests "*MoneyMapperTest"` → FAIL (MoneyMapper missing).
- [ ] **Step 3: `MoneyMapper`** — `examples/acme-bank/bank-contracts/src/main/java/com/acme/bank/contracts/MoneyMapper.java`:
```java
package com.acme.bank.contracts;

import com.acme.money.Assets;
import com.acme.money.Money;

/** Bridges the domain {@link Money} value type to the Avro wire contract (string amount + asset). */
public final class MoneyMapper {

    private MoneyMapper() {}

    public static com.acme.bank.contracts.avro.Money toAvro(Money money) {
        return com.acme.bank.contracts.avro.Money.newBuilder()
                .setAmount(money.toAmountString())
                .setAsset(money.asset().code())
                .build();
    }

    public static Money fromAvro(com.acme.bank.contracts.avro.Money avro) {
        return Money.of(avro.getAmount().toString(), Assets.of(avro.getAsset().toString()));
    }
}
```
- [ ] **Step 4: run, PASS** — `gradle :examples:acme-bank:bank-contracts:test --tests "*MoneyMapperTest"` → PASS.
- [ ] **Step 5: format + commit**
```bash
gradle :examples:acme-bank:bank-contracts:spotlessApply
git add examples/acme-bank/bank-contracts/src
git commit -m "feat(bank-contracts): MoneyMapper bridging Money <-> Avro (string wire, no float)"
```

---

## Task 4: `acme.bank-service-conventions` build plugin

**Files:** `build-logic/src/main/kotlin/acme.bank-service-conventions.gradle.kts`.

- [ ] **Step 1: convention plugin** — Create `build-logic/src/main/kotlin/acme.bank-service-conventions.gradle.kts`. It applies the java conventions and adds the DDD/architecture toolchain every bank service uses. Because precompiled script plugins can't use the `libs` catalog accessor directly, declare coordinates as strings (versions kept in sync with the catalog):
```kotlin
plugins {
    id("acme.java-conventions")
}

dependencies {
    // jMolecules DDD stereotypes (architecturally evident code) — versions via the jMolecules BOM.
    "implementation"(platform("org.jmolecules:jmolecules-bom:2025.0.2"))
    "implementation"("org.jmolecules:jmolecules-ddd")
    "implementation"("org.jmolecules:jmolecules-events")

    // Architecture fitness functions.
    "testImplementation"(platform("org.jmolecules:jmolecules-bom:2025.0.2"))
    "testImplementation"("com.tngtech.archunit:archunit-junit5:1.4.2")
    "testImplementation"("org.jmolecules.integrations:jmolecules-archunit")
}
```
> This plugin gives a bank service: Java 21 toolchain + Spotless (from `acme.java-conventions`), jMolecules annotations on the main classpath, and ArchUnit + jmolecules-archunit on the test classpath. BANK-1..5 services apply `id("acme.bank-service-conventions")`.
- [ ] **Step 2: verify build-logic compiles** — `gradle :examples:acme-bank:bank-contracts:help -q` (forces build-logic compilation as part of the build). Expected: BUILD SUCCESSFUL.
- [ ] **Step 3: commit**
```bash
git add build-logic/src/main/kotlin/acme.bank-service-conventions.gradle.kts
git commit -m "build: acme.bank-service-conventions plugin (jMolecules + ArchUnit toolchain)"
```

---

## Task 5: `compose.bank.yaml` local stack

**Files:** `examples/acme-bank/compose.bank.yaml`.

- [ ] **Step 1:** Create `examples/acme-bank/compose.bank.yaml` — the backing infra for the bank example (services run separately). Reuse the patterns from the repo-root `compose.yaml` but namespaced for the bank:
```yaml
name: acme-bank-local

services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: bank
      POSTGRES_USER: bank
      POSTGRES_PASSWORD: bank
    ports: ["5432:5432"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U bank"]
      interval: 5s
      timeout: 3s
      retries: 10

  redpanda:
    image: redpandadata/redpanda:v24.2.7
    command:
      - redpanda
      - start
      - --overprovisioned
      - --smp=1
      - --memory=1G
      - --reserve-memory=0M
      - --node-id=0
      - --kafka-addr=PLAINTEXT://0.0.0.0:9092
      - --advertise-kafka-addr=PLAINTEXT://localhost:9092
    ports:
      - "9092:9092"   # Kafka
      - "8081:8081"   # Schema Registry
    healthcheck:
      test: ["CMD-SHELL", "rpk cluster health | grep -q 'Healthy:.*true'"]
      interval: 5s
      timeout: 5s
      retries: 20

  keycloak:
    image: quay.io/keycloak/keycloak:26.2
    command: ["start-dev"]
    environment:
      KEYCLOAK_ADMIN: admin
      KEYCLOAK_ADMIN_PASSWORD: admin
    ports: ["8082:8080"]   # 8082 to avoid colliding with Schema Registry on 8081

  observability:
    image: grafana/otel-lgtm:latest
    ports:
      - "3000:3000"   # Grafana
      - "4317:4317"   # OTLP gRPC
      - "4318:4318"   # OTLP HTTP
```
- [ ] **Step 2: validate** — `docker compose -f examples/acme-bank/compose.bank.yaml config` → parses with no error (do NOT `up`).
- [ ] **Step 3: commit**
```bash
git add examples/acme-bank/compose.bank.yaml
git commit -m "build: acme-bank local docker-compose stack"
```

---

## Task 6: Full build + ADR

- [ ] **Step 1:** `gradle build` → BUILD SUCCESSFUL (bank-contracts codegen + MoneyMapper test green; no regression).
- [ ] **Step 2:** Create `docs/decisions/0012-acme-bank-skeleton.md`:
```markdown
---
status: accepted
date: 2026-06-18
---

# acme-bank skeleton: Avro contracts + service conventions

## Decision Outcome

- `examples/acme-bank` is the enterprise banking example: choreographed microservices, each internally
  hexagonal + DDD + Spring Modulith, exercising every starter.
- `bank-contracts` holds shared Avro integration-event schemas (`Money` as a `{amount,asset}` string
  record so no float crosses the wire); `MoneyMapper` bridges the `acme-money` `Money` value type to/from
  the Avro contract. Schemas grow per service in BANK-1..5.
- `acme.bank-service-conventions` is the one build plugin each bank service applies: Java 21 + Spotless
  (from `acme.java-conventions`) + jMolecules DDD stereotypes (main) + ArchUnit + jmolecules-archunit
  (test) — making hexagonal/DDD boundaries enforceable fitness functions.
- `compose.bank.yaml` provides Postgres, Redpanda (+ Schema Registry), Keycloak (port 8082), and the
  otel-lgtm observability stack.

Full design: `docs/superpowers/specs/2026-06-18-acme-bank-design.md`.
```
- [ ] **Step 3:** Commit:
```bash
git add docs/decisions/0012-acme-bank-skeleton.md
git commit -m "docs: ADR 0012 acme-bank skeleton + Avro contracts"
```

---

## Done criteria for BANK-0.5

- `gradle build` green; `bank-contracts` codegens the Avro types; `MoneyMapper` round-trips Money through Avro (incl. crypto precision).
- `acme.bank-service-conventions` plugin available for BANK-1..5 services.
- `compose.bank.yaml` validates.

---

## Self-review notes

- **Spec coverage:** §8 Avro contracts ✓ (Money + 2 events; rest per service), `Money` string-on-wire ✓
  (MoneyMapper), §5 service conventions (hexagonal/DDD/ArchUnit toolchain) ✓ (bank-service-conventions),
  §4 example skeleton + compose ✓. Remaining event schemas + the services themselves are BANK-1..5.
- **Type consistency:** `MoneyMapper.toAvro/fromAvro`, `com.acme.bank.contracts.avro.Money` (amount/asset
  strings), `Money.toAmountString()/asset().code()`, `Assets.of(String)` — consistent with BANK-0.
- **No placeholders.** Concrete schemas/code/compose throughout.
- **Risk:** Avro cross-file `Money` reference resolution — Task 2 Step 7 calls it out with the fallback;
  the davidmc24 plugin resolves same-source-set references by default.
