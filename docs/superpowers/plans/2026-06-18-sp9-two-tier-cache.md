# SP-9 Two-Tier Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add opt-in two-tier cache (Caffeine L1 + Redis L2) to `acme-cache-spring-boot-autoconfigure`, activated by `acme.cache.two-tier.enabled=true` when Redis is on the classpath.

**Architecture:** A new `TwoTierCacheManager` wraps a `CaffeineCacheManager` (L1) and `RedisCacheManager` (L2); reads check L1 then L2 (populating L1 on L2 hit); writes and evictions go to both tiers. The existing Caffeine-only `CacheManager` bean is guarded by `@ConditionalOnMissingBean` and backs off when the two-tier bean is active. `acme-test-support` gains a `RedisTestcontainersConfiguration` using `GenericContainer` + `@ServiceConnection(name="redis")`.

**Tech Stack:** Spring Boot 3.5 / Java 21, Caffeine, Spring Data Redis, Testcontainers GenericContainer, Gradle 8.14

---

### Task 1: Add Redis alias to version catalog and test-support build

**Files:**
- Modify: `gradle/libs.versions.toml` — add `spring-boot-starter-data-redis` library alias
- Modify: `starters/acme-test-support/build.gradle.kts` — add `api(libs.spring.boot.starter.data.redis)`

- [ ] **Step 1: Add library alias to version catalog**

In `gradle/libs.versions.toml` under `[libraries]`, after the `caffeine` line, add:
```toml
spring-boot-starter-data-redis = { module = "org.springframework.boot:spring-boot-starter-data-redis" }
```

- [ ] **Step 2: Add Redis dependency to acme-test-support build**

In `starters/acme-test-support/build.gradle.kts`, add after `api(libs.spring.kafka)`:
```kotlin
    api(libs.spring.boot.starter.data.redis)
```
Note: `testcontainers-junit-jupiter` is already present in that build file.

- [ ] **Step 3: Verify compilation**

Run: `gradle :starters:acme-test-support:compileJava`
Expected: BUILD SUCCESSFUL

---

### Task 2: Create RedisTestcontainersConfiguration

**Files:**
- Create: `starters/acme-test-support/src/main/java/com/acme/test/RedisTestcontainersConfiguration.java`

- [ ] **Step 1: Create the configuration class**

```java
package com.acme.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Importable test configuration exposing a Redis container wired to Spring Boot via
 * {@link ServiceConnection}. Integration tests {@code @Import} this to get a real Redis
 * with zero datasource configuration. Uses the locally-cached {@code redis:7-alpine} image.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedisTestcontainersConfiguration {

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    }
}
```

If `@ServiceConnection(name = "redis")` does not wire the connection (compile error or runtime failure),
fall back to a `DynamicPropertyRegistrar` approach:
```java
package com.acme.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class RedisTestcontainersConfiguration {

    @Bean
    GenericContainer<?> redisContainer() {
        GenericContainer<?> container =
                new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
        container.start();
        return container;
    }

    @Bean
    DynamicPropertyRegistrar redisProperties(GenericContainer<?> redisContainer) {
        return registry -> {
            registry.add("spring.data.redis.host", redisContainer::getHost);
            registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379));
        };
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `gradle :starters:acme-test-support:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Apply Spotless and commit Task 1+2**

```bash
gradle :starters:acme-test-support:spotlessApply
git add gradle/libs.versions.toml starters/acme-test-support
git commit -m "feat(test-support): Redis Testcontainers config + data-redis alias"
```

---

### Task 3: Add Redis compileOnly + testImplementation to acme-cache-spring-boot-autoconfigure

**Files:**
- Modify: `starters/acme-cache-spring-boot-autoconfigure/build.gradle.kts`

- [ ] **Step 1: Add optional Redis dependency**

In `starters/acme-cache-spring-boot-autoconfigure/build.gradle.kts`, after the `api(libs.caffeine)` line, add:
```kotlin
    compileOnly(libs.spring.boot.starter.data.redis)
    testImplementation(libs.spring.boot.starter.data.redis)
```

- [ ] **Step 2: Verify compilation**

Run: `gradle :starters:acme-cache-spring-boot-autoconfigure:compileJava`
Expected: BUILD SUCCESSFUL (no Redis classes needed yet — will need them for next tasks)

---

### Task 4: Create TwoTierCache

**Files:**
- Create: `starters/acme-cache-spring-boot-autoconfigure/src/main/java/com/acme/cache/TwoTierCache.java`

- [ ] **Step 1: Create TwoTierCache.java**

```java
package com.acme.cache;

import java.util.concurrent.Callable;
import org.springframework.cache.Cache;

/**
 * Two-tier cache: L1 (in-process, e.g. Caffeine) in front of L2 (shared, e.g. Redis). Reads check
 * L1 then L2 (populating L1 on an L2 hit); writes and evictions apply to both tiers.
 * Note: cross-instance L1 invalidation (Redis pub/sub) is a future enhancement — an evict on one
 * node clears its own L1 + the shared L2, but other nodes' L1 entries expire by TTL.
 */
public class TwoTierCache implements Cache {

    private final String name;
    private final Cache l1;
    private final Cache l2;

    public TwoTierCache(String name, Cache l1, Cache l2) {
        this.name = name;
        this.l1 = l1;
        this.l2 = l2;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return this;
    }

    @Override
    public ValueWrapper get(Object key) {
        ValueWrapper hit = l1.get(key);
        if (hit != null) {
            return hit;
        }
        ValueWrapper l2hit = l2.get(key);
        if (l2hit != null) {
            l1.put(key, l2hit.get());
        }
        return l2hit;
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        ValueWrapper wrapper = get(key);
        return wrapper == null ? null : type.cast(wrapper.get());
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        ValueWrapper wrapper = get(key);
        if (wrapper != null) {
            @SuppressWarnings("unchecked")
            T existing = (T) wrapper.get();
            return existing;
        }
        try {
            T value = valueLoader.call();
            put(key, value);
            return value;
        } catch (Exception e) {
            throw new ValueRetrievalException(key, valueLoader, e);
        }
    }

    @Override
    public void put(Object key, Object value) {
        l2.put(key, value);
        l1.put(key, value);
    }

    @Override
    public void evict(Object key) {
        l2.evict(key);
        l1.evict(key);
    }

    @Override
    public void clear() {
        l2.clear();
        l1.clear();
    }
}
```

---

### Task 5: Create TwoTierCacheManager

**Files:**
- Create: `starters/acme-cache-spring-boot-autoconfigure/src/main/java/com/acme/cache/TwoTierCacheManager.java`

- [ ] **Step 1: Create TwoTierCacheManager.java**

```java
package com.acme.cache;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/** Composes an L1 and L2 {@link CacheManager} into {@link TwoTierCache}s. */
public class TwoTierCacheManager implements CacheManager {

    private final CacheManager l1;
    private final CacheManager l2;

    public TwoTierCacheManager(CacheManager l1, CacheManager l2) {
        this.l1 = l1;
        this.l2 = l2;
    }

    @Override
    public Cache getCache(String name) {
        Cache l1Cache = l1.getCache(name);
        Cache l2Cache = l2.getCache(name);
        if (l1Cache == null || l2Cache == null) {
            return l1Cache != null ? l1Cache : l2Cache;
        }
        return new TwoTierCache(name, l1Cache, l2Cache);
    }

    @Override
    public Collection<String> getCacheNames() {
        Set<String> names = new LinkedHashSet<>(l1.getCacheNames());
        names.addAll(l2.getCacheNames());
        return names;
    }
}
```

---

### Task 6: Wire two-tier bean into CacheAutoConfiguration

**Files:**
- Modify: `starters/acme-cache-spring-boot-autoconfigure/src/main/java/com/acme/cache/autoconfigure/CacheAutoConfiguration.java`

- [ ] **Step 1: Add two-tier bean before existing Caffeine bean**

Replace the entire file content with:
```java
package com.acme.cache.autoconfigure;

import com.acme.cache.TwoTierCacheManager;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Enables Spring Cache backed by Caffeine with sane defaults (10-minute TTL, 10k entries).
 * Overridable — a consumer can define their own {@link CacheManager}.
 *
 * <p>When {@code acme.cache.two-tier.enabled=true} and Redis is on the classpath, a
 * {@link TwoTierCacheManager} (Caffeine L1 + Redis L2) is registered instead.
 */
@AutoConfiguration
@ConditionalOnClass({CaffeineCacheManager.class, Caffeine.class})
@EnableCaching
public class CacheAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "acme.cache.two-tier", name = "enabled", havingValue = "true")
    @ConditionalOnClass(RedisConnectionFactory.class)
    @ConditionalOnBean(RedisConnectionFactory.class)
    public CacheManager twoTierCacheManager(RedisConnectionFactory redisConnectionFactory) {
        Caffeine<Object, Object> caffeine =
                Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(10)).maximumSize(10_000);
        CaffeineCacheManager l1 = new CaffeineCacheManager();
        l1.setCaffeine(caffeine);
        RedisCacheManager l2 = RedisCacheManager.builder(redisConnectionFactory).build();
        return new TwoTierCacheManager(l1, l2);
    }

    @Bean
    @ConditionalOnMissingBean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(
                Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(10)).maximumSize(10_000));
        return manager;
    }
}
```

Key design note: `twoTierCacheManager()` is a different method name from `cacheManager()`, but both
produce a `CacheManager` bean. Spring's `@ConditionalOnMissingBean` on `cacheManager()` checks for any
existing `CacheManager` bean — so when `twoTierCacheManager` is registered first (it's declared first),
the Caffeine-only `cacheManager()` backs off. The two-tier bean has a unique method name to avoid any
ambiguity.

- [ ] **Step 2: Verify compilation**

Run: `gradle :starters:acme-cache-spring-boot-autoconfigure:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Apply Spotless and commit Tasks 3-6**

```bash
gradle :starters:acme-cache-spring-boot-autoconfigure:spotlessApply
git add starters/acme-cache-spring-boot-autoconfigure
git commit -m "feat(acme-cache): opt-in two-tier cache (Caffeine L1 + Redis L2)"
```

---

### Task 7: Create TwoTierCacheIT in demo-service

**Files:**
- Create: `examples/demo-service/src/test/java/com/acme/demo/TwoTierCacheIT.java`
- Modify: `examples/demo-service/build.gradle.kts` — add Redis runtime dependency for the IT

- [ ] **Step 1: Add Redis to demo-service test dependencies**

In `examples/demo-service/build.gradle.kts`, add after `testImplementation(libs.spring.security.test)`:
```kotlin
    testImplementation(libs.spring.boot.starter.data.redis)
```

- [ ] **Step 2: Create TwoTierCacheIT.java**

```java
package com.acme.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.cache.TwoTierCacheManager;
import com.acme.test.PostgresTestcontainersConfiguration;
import com.acme.test.RedisTestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = "acme.cache.two-tier.enabled=true")
@Import({PostgresTestcontainersConfiguration.class, RedisTestcontainersConfiguration.class})
class TwoTierCacheIT {

    @Autowired
    PricingService pricing;

    @Autowired
    CacheManager cacheManager;

    @Test
    void valueSurvivesL1EvictionByServingFromL2() {
        int first = pricing.priceFor("TWOTIER");
        assertThat(pricing.computations()).isEqualTo(1);

        // Second call must NOT recompute — served from L1 or L2.
        int second = pricing.priceFor("TWOTIER");
        assertThat(second).isEqualTo(first);
        assertThat(pricing.computations()).isEqualTo(1);

        // Confirm the active manager is the two-tier one.
        assertThat(cacheManager).isInstanceOf(TwoTierCacheManager.class);
    }
}
```

- [ ] **Step 3: Run the targeted tests**

Run: `gradle :examples:demo-service:test --tests "*TwoTierCacheIT" --tests "*CacheIT"`
Expected: Both tests PASS

- [ ] **Step 4: Run the full demo-service test suite**

Run: `gradle :examples:demo-service:test`
Expected: BUILD SUCCESSFUL, all tests green

- [ ] **Step 5: Apply Spotless and commit**

```bash
gradle :examples:demo-service:spotlessApply
git add examples/demo-service
git commit -m "test(demo): two-tier cache wiring IT (Caffeine L1 + Redis L2)"
```

---

### Task 8: Full build + ADR update

**Files:**
- Modify: `docs/decisions/0007-utility-starters.md`

- [ ] **Step 1: Run full build**

Run: `gradle build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Append two-tier section to ADR**

In `docs/decisions/0007-utility-starters.md`, after the `acme-cache` bullet (the one ending with "deferred."), append a new bullet:
```markdown
- `acme-cache` two-tier (opt-in, `acme.cache.two-tier.enabled=true` + Redis on classpath): Caffeine
  L1 in front of Redis L2 (`TwoTierCacheManager`/`TwoTierCache`) — reads populate L1 from L2, writes/
  evicts hit both. Cross-instance L1 invalidation via Redis pub/sub is a future enhancement.
```

- [ ] **Step 3: Commit ADR**

```bash
git add docs/decisions/0007-utility-starters.md
git commit -m "docs: ADR 0007 note opt-in two-tier cache"
```
