# SP-6: Utility Starters (cache, resilience, feature flags) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** Add three small reusable starters — `acme-cache` (Caffeine), `acme-resilience` (Resilience4j), `acme-featureflags` (OpenFeature) — each with a default-on, overridable auto-configuration, and prove each in `demo-service` with an in-process integration test (no containers).

**Architecture:** Each starter is an autoconfigure module + thin starter following the established pattern (`@ConditionalOnClass`, `@ConditionalOnMissingBean`, `AutoConfiguration.imports`). `acme-cache` enables Spring Cache backed by Caffeine with sane defaults (Redis L2 / true two-tier deferred). `acme-resilience` brings Resilience4j Spring Boot 3 so `@Retry`/`@CircuitBreaker` annotations work. `acme-featureflags` wires the OpenFeature `Client` over an overridable in-memory provider. All three are verified in-process (Caffeine caching, Resilience4j retry, OpenFeature flag evaluation) — fast and container-free.

**Tech Stack:** Java 21, Spring Boot 3.5.6, Caffeine (Boot-managed), Resilience4j 2.4.0, OpenFeature SDK 1.20.2, JUnit 5 + AssertJ.

> Spec: `docs/superpowers/specs/2026-06-17-acme-boilerplate-design.md` §5 (acme-cache / acme-resilience / acme-featureflags). Builds on SP-0..5.
> **Environment (every task):** work from `/Users/npden4ik/Projects/java-boilerplate`; system `gradle` (8.14) NEVER `./gradlew`; JDK 21 toolchain; Maven Central fast. `gradle <module>:spotlessApply` before each commit. Demo ITs now require a JWT for HTTP — but these tests call beans directly (no HTTP), so security does not apply.

---

## Task 1: Version catalog

Modify `gradle/libs.versions.toml` (keep existing). Add to `[versions]`:
```toml
resilience4j = "2.4.0"
openfeature = "1.20.2"
```
Add to `[libraries]`:
```toml
spring-boot-starter-cache = { module = "org.springframework.boot:spring-boot-starter-cache" }
spring-boot-starter-aop = { module = "org.springframework.boot:spring-boot-starter-aop" }
caffeine = { module = "com.github.ben-manes.caffeine:caffeine" }
resilience4j-spring-boot3 = { module = "io.github.resilience4j:resilience4j-spring-boot3", version.ref = "resilience4j" }
openfeature-sdk = { module = "dev.openfeature:sdk", version.ref = "openfeature" }
```
- [ ] **Step 1:** Apply. **Step 2:** `gradle :platform:acme-bom:help -q` → BUILD SUCCESSFUL. **Step 3:** Commit:
```bash
git add gradle/libs.versions.toml
git commit -m "build: add cache/resilience/featureflags library aliases"
```

---

## Task 2: `acme-cache` starter (Caffeine)

**Files:** settings.gradle.kts (modify); `acme-cache-spring-boot-autoconfigure` (build.gradle.kts, `CacheAutoConfiguration.java`, imports); `acme-cache-spring-boot-starter/build.gradle.kts`.

- [ ] **Step 1: settings** — add to `include(...)` (after the security starter entry):
```kotlin
    "starters:acme-cache-spring-boot-autoconfigure",
    "starters:acme-cache-spring-boot-starter",
```
- [ ] **Step 2: dirs**
```bash
mkdir -p starters/acme-cache-spring-boot-autoconfigure/src/main/java/com/acme/cache/autoconfigure \
  starters/acme-cache-spring-boot-autoconfigure/src/main/resources/META-INF/spring \
  starters/acme-cache-spring-boot-starter
```
- [ ] **Step 3: autoconfigure build** — `starters/acme-cache-spring-boot-autoconfigure/build.gradle.kts`:
```kotlin
plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(libs.spring.boot.autoconfigure)
    api(libs.spring.boot.starter.cache)
    api(libs.caffeine)
}
```
- [ ] **Step 4: auto-config** — `.../com/acme/cache/autoconfigure/CacheAutoConfiguration.java`:
```java
package com.acme.cache.autoconfigure;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;

/**
 * Enables Spring Cache backed by Caffeine with sane defaults (10-minute TTL, 10k entries).
 * Overridable — a consumer can define their own {@link CacheManager}. (Redis L2 / two-tier deferred.)
 */
@AutoConfiguration
@ConditionalOnClass({CaffeineCacheManager.class, Caffeine.class})
@EnableCaching
public class CacheAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(10))
                .maximumSize(10_000));
        return manager;
    }
}
```
- [ ] **Step 5: imports** — `.../resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
```
com.acme.cache.autoconfigure.CacheAutoConfiguration
```
- [ ] **Step 6: thin starter** — `starters/acme-cache-spring-boot-starter/build.gradle.kts`:
```kotlin
plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(project(":starters:acme-cache-spring-boot-autoconfigure"))
    api(libs.spring.boot.starter.cache)
    api(libs.caffeine)
}
```
- [ ] **Step 7: verify** — `gradle :starters:acme-cache-spring-boot-starter:assemble` → BUILD SUCCESSFUL.
- [ ] **Step 8: commit**
```bash
gradle :starters:acme-cache-spring-boot-autoconfigure:spotlessApply
git add settings.gradle.kts starters/acme-cache-spring-boot-autoconfigure starters/acme-cache-spring-boot-starter
git commit -m "feat(acme-cache): Caffeine-backed Spring Cache starter"
```

---

## Task 3: `acme-resilience` starter (Resilience4j)

**Files:** settings.gradle.kts (modify); `acme-resilience-spring-boot-starter/build.gradle.kts` (thin only — Resilience4j self-configures).

- [ ] **Step 1: settings** — add to `include(...)`:
```kotlin
    "starters:acme-resilience-spring-boot-starter",
```
- [ ] **Step 2: dir** — `mkdir -p starters/acme-resilience-spring-boot-starter`
- [ ] **Step 3: thin starter** — `starters/acme-resilience-spring-boot-starter/build.gradle.kts` (Resilience4j Spring Boot 3 auto-configures `@Retry`/`@CircuitBreaker` aspects; needs AOP):
```kotlin
plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(libs.resilience4j.spring.boot3)
    api(libs.spring.boot.starter.aop)
}
```
- [ ] **Step 4: verify** — `gradle :starters:acme-resilience-spring-boot-starter:assemble` → BUILD SUCCESSFUL.
- [ ] **Step 5: commit**
```bash
git add settings.gradle.kts starters/acme-resilience-spring-boot-starter
git commit -m "feat(acme-resilience): Resilience4j Spring Boot 3 starter"
```

---

## Task 4: `acme-featureflags` starter (OpenFeature)

**Files:** settings.gradle.kts (modify); `acme-featureflags-spring-boot-autoconfigure` (build, `FeatureFlagsAutoConfiguration.java`, imports); `acme-featureflags-spring-boot-starter/build.gradle.kts`.

> **OpenFeature 1.20.2 API note:** `dev.openfeature.sdk.OpenFeatureAPI.getInstance()`, `api.setProviderAndWait(provider)` (may throw `OpenFeatureError`), `api.getClient()` returns a `Client`. An empty default provider can be `new dev.openfeature.sdk.NoOpProvider()`. If a method name/signature differs in 1.20.2, adapt minimally — the demo's passing test is the contract.

- [ ] **Step 1: settings** — add:
```kotlin
    "starters:acme-featureflags-spring-boot-autoconfigure",
    "starters:acme-featureflags-spring-boot-starter",
```
- [ ] **Step 2: dirs**
```bash
mkdir -p starters/acme-featureflags-spring-boot-autoconfigure/src/main/java/com/acme/featureflags/autoconfigure \
  starters/acme-featureflags-spring-boot-autoconfigure/src/main/resources/META-INF/spring \
  starters/acme-featureflags-spring-boot-starter
```
- [ ] **Step 3: autoconfigure build** — `starters/acme-featureflags-spring-boot-autoconfigure/build.gradle.kts`:
```kotlin
plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(libs.spring.boot.autoconfigure)
    api(libs.openfeature.sdk)
}
```
- [ ] **Step 4: auto-config** — `.../com/acme/featureflags/autoconfigure/FeatureFlagsAutoConfiguration.java`:
```java
package com.acme.featureflags.autoconfigure;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.NoOpProvider;
import dev.openfeature.sdk.OpenFeatureAPI;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Wires an OpenFeature {@link Client} over an overridable {@link FeatureProvider}.
 * The default is a {@link NoOpProvider}; a consumer supplies their own provider bean (e.g. an
 * in-memory or flagd provider) to drive real flag values.
 */
@AutoConfiguration
@ConditionalOnClass(OpenFeatureAPI.class)
public class FeatureFlagsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FeatureProvider featureProvider() {
        return new NoOpProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public Client featureFlagsClient(FeatureProvider provider) {
        OpenFeatureAPI api = OpenFeatureAPI.getInstance();
        api.setProviderAndWait(provider);
        return api.getClient();
    }
}
```
- [ ] **Step 5: imports** — `.../resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
```
com.acme.featureflags.autoconfigure.FeatureFlagsAutoConfiguration
```
- [ ] **Step 6: thin starter** — `starters/acme-featureflags-spring-boot-starter/build.gradle.kts`:
```kotlin
plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(project(":starters:acme-featureflags-spring-boot-autoconfigure"))
    api(libs.openfeature.sdk)
}
```
- [ ] **Step 7: verify** — `gradle :starters:acme-featureflags-spring-boot-starter:assemble` → BUILD SUCCESSFUL. (If `setProviderAndWait` throws a checked exception, wrap/handle it; report the adaptation.)
- [ ] **Step 8: commit**
```bash
gradle :starters:acme-featureflags-spring-boot-autoconfigure:spotlessApply
git add settings.gradle.kts starters/acme-featureflags-spring-boot-autoconfigure starters/acme-featureflags-spring-boot-starter
git commit -m "feat(acme-featureflags): OpenFeature client starter (overridable provider)"
```

---

## Task 5: demo wiring + in-process ITs for all three

**Files:** demo build.gradle.kts (modify); `PricingService.java` (@Cacheable), `FlakyService.java` (@Retry), demo OpenFeature provider config; `CacheIT.java`, `ResilienceIT.java`, `FeatureFlagsIT.java`.

- [ ] **Step 1: deps** — in `examples/demo-service/build.gradle.kts` `dependencies { }`, after the security starter:
```kotlin
    implementation(project(":starters:acme-cache-spring-boot-starter"))
    implementation(project(":starters:acme-resilience-spring-boot-starter"))
    implementation(project(":starters:acme-featureflags-spring-boot-starter"))
```

- [ ] **Step 2: cached service** — `examples/demo-service/src/main/java/com/acme/demo/PricingService.java`:
```java
package com.acme.demo;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/** Demonstrates Caffeine caching: repeated lookups for the same sku hit the cache, not the method. */
@Service
public class PricingService {

    private final AtomicInteger computations = new AtomicInteger();

    @Cacheable("prices")
    public int priceFor(String sku) {
        computations.incrementAndGet();
        return sku.length() * 100;
    }

    public int computations() {
        return computations.get();
    }
}
```

- [ ] **Step 3: flaky service** — `examples/demo-service/src/main/java/com/acme/demo/FlakyService.java`:
```java
package com.acme.demo;

import io.github.resilience4j.retry.annotation.Retry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

/** Demonstrates Resilience4j @Retry: fails the first two calls, then succeeds. */
@Service
public class FlakyService {

    private final AtomicInteger attempts = new AtomicInteger();

    @Retry(name = "flaky")
    public String call() {
        if (attempts.incrementAndGet() < 3) {
            throw new IllegalStateException("transient failure " + attempts.get());
        }
        return "ok";
    }

    public int attempts() {
        return attempts.get();
    }
}
```

- [ ] **Step 4: Resilience4j retry config** — append to `examples/demo-service/src/main/resources/application.yaml` (top-level, sibling of `spring:`/`server:`/`management:`):
```yaml
resilience4j:
  retry:
    instances:
      flaky:
        max-attempts: 3
        wait-duration: 10ms
```

- [ ] **Step 5: demo feature provider** — `examples/demo-service/src/main/java/com/acme/demo/FeatureFlagsConfig.java`:
```java
package com.acme.demo;

import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.providers.memory.Flag;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Supplies an in-memory OpenFeature provider with one demo flag (overrides the starter NoOpProvider). */
@Configuration
public class FeatureFlagsConfig {

    @Bean
    public FeatureProvider featureProvider() {
        Flag<Boolean> newCheckout = Flag.builder()
                .variant("on", true)
                .variant("off", false)
                .defaultVariant("on")
                .build();
        return new InMemoryProvider(Map.of("new-checkout", newCheckout));
    }
}
```
> If the OpenFeature 1.20.2 `InMemoryProvider`/`Flag` builder API differs, adapt to produce a provider where boolean flag `new-checkout` evaluates to `true`; report the change.

- [ ] **Step 6: cache IT** — `examples/demo-service/src/test/java/com/acme/demo/CacheIT.java`:
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
class CacheIT {

    @Autowired
    PricingService pricing;

    @Test
    void repeatedLookupsAreCached() {
        int first = pricing.priceFor("ABCDE");
        int second = pricing.priceFor("ABCDE");
        assertThat(second).isEqualTo(first);
        assertThat(pricing.computations()).isEqualTo(1); // method body ran once; second call cached
    }
}
```

- [ ] **Step 7: resilience IT** — `examples/demo-service/src/test/java/com/acme/demo/ResilienceIT.java`:
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
class ResilienceIT {

    @Autowired
    FlakyService flaky;

    @Test
    void retryRecoversAfterTransientFailures() {
        String result = flaky.call(); // fails twice, then succeeds on the 3rd attempt
        assertThat(result).isEqualTo("ok");
        assertThat(flaky.attempts()).isEqualTo(3);
    }
}
```

- [ ] **Step 8: feature flags IT** — `examples/demo-service/src/test/java/com/acme/demo/FeatureFlagsIT.java`:
```java
package com.acme.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.test.PostgresTestcontainersConfiguration;
import dev.openfeature.sdk.Client;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(PostgresTestcontainersConfiguration.class)
class FeatureFlagsIT {

    @Autowired
    Client featureClient;

    @Test
    void evaluatesInMemoryFlag() {
        assertThat(featureClient.getBooleanValue("new-checkout", false)).isTrue();
        assertThat(featureClient.getBooleanValue("unknown-flag", false)).isFalse();
    }
}
```

- [ ] **Step 9: run the three ITs** — `gradle :examples:demo-service:test --tests "*CacheIT" --tests "*ResilienceIT" --tests "*FeatureFlagsIT"` → PASS.
> Debug notes: cache — if `computations()==2`, caching isn't active (confirm `@EnableCaching` from the starter + `spring.cache.type` not overridden). resilience — if it throws instead of retrying, confirm the `flaky` retry instance config + that `@Retry` aspect is active (AOP). featureflags — if `new-checkout` is false, the demo `InMemoryProvider` bean didn't override the starter `NoOpProvider` (it should via the same bean type + `@ConditionalOnMissingBean`).

- [ ] **Step 10: full demo suite** — `gradle :examples:demo-service:test` → all green.
- [ ] **Step 11: commit**
```bash
gradle :examples:demo-service:spotlessApply
git add examples/demo-service
git commit -m "feat(demo): wire cache/resilience/featureflags + in-process ITs"
```

---

## Task 6: Full build + ADR

- [ ] **Step 1:** `gradle build` → BUILD SUCCESSFUL. Fix Spotless via `spotlessApply`; debug real failures; BLOCKED if stuck.
- [ ] **Step 2:** Create `docs/decisions/0007-utility-starters.md`:
```markdown
---
status: accepted
date: 2026-06-17
---

# Utility starters: cache (Caffeine), resilience (Resilience4j), feature flags (OpenFeature)

## Decision Outcome

- `acme-cache`: Spring Cache backed by Caffeine with sane defaults (10-min TTL, 10k entries),
  overridable `CacheManager`. Redis L2 / true two-tier with cross-instance invalidation deferred.
- `acme-resilience`: Resilience4j Spring Boot 3 (2.4.0) so `@Retry`/`@CircuitBreaker`/`@Bulkhead`
  annotations work; instances configured via `resilience4j.*` properties.
- `acme-featureflags`: OpenFeature SDK (1.20.2) `Client` over an overridable `FeatureProvider`
  (default `NoOpProvider`); a consumer supplies an in-memory/flagd/vendor provider bean.
- Each is verified in-process (Caffeine caching hit-count, Resilience4j retry recovery, OpenFeature
  flag evaluation) — no containers required.

Full detail: `docs/superpowers/specs/2026-06-17-acme-boilerplate-design.md` §5.
```
- [ ] **Step 3:** Commit:
```bash
git add docs/decisions/0007-utility-starters.md
git commit -m "docs: ADR 0007 utility starters (cache, resilience, feature flags)"
```

---

## Done criteria for SP-6

- `gradle build` green (all modules, Spotless, all tests).
- Three new starters (`acme-cache`, `acme-resilience`, `acme-featureflags`), each default-on + overridable.
- demo ITs prove: Caffeine caches (body runs once), Resilience4j retries to success, OpenFeature flag evaluates true.

---

## Self-review notes

- **Spec coverage (§5):** cache (Caffeine L1 ✓; Redis L2 / two-tier deferred-documented), resilience (Resilience4j presets ✓), feature flags (OpenFeature + overridable provider ✓).
- **Type consistency:** `PricingService.computations()`, `FlakyService.attempts()`, `Client.getBooleanValue` used in the ITs; provider bean type `FeatureProvider` overrides the starter default via `@ConditionalOnMissingBean`.
- **No placeholders.** Concrete throughout; OpenFeature `InMemoryProvider`/`Flag` + `setProviderAndWait` API flagged for minimal adaptation against 1.20.2, with the passing test as contract.
- **No-container tests:** all three ITs exercise beans directly (cache/retry/flag), so they are fast and unaffected by HTTP security; they still boot the full context (hence the Postgres Testcontainers import to satisfy the datasource the demo requires).
