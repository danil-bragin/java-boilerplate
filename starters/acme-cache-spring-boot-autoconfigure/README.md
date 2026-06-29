# acme-cache-spring-boot-autoconfigure

Auto-configures Spring's cache abstraction backed by Caffeine, with an optional two-tier (Caffeine L1 + Redis L2) mode. Enables `@Cacheable`/`@CacheEvict` with sane defaults. The default `CacheManager` is `@ConditionalOnMissingBean`, so consumers can override it.

## What it configures
- `@EnableCaching` — activates Spring's caching annotations.
- `CacheManager cacheManager` — `CaffeineCacheManager` with a 10-minute write TTL and 10,000-entry max. Gated `@ConditionalOnClass({CaffeineCacheManager.class, Caffeine.class})`, registered `@ConditionalOnMissingBean`.
- `CacheManager twoTierCacheManager` — a `TwoTierCacheManager` composing Caffeine L1 (10 min / 10k) over a Redis L2 (`RedisCacheManager`). Reads check L1 then L2 (populating L1 on an L2 hit); writes and evictions hit both tiers. Activated only when `acme.cache.two-tier.enabled=true` with Redis on the classpath and a `RedisConnectionFactory` bean present. Runs `@AutoConfiguration(after = RedisAutoConfiguration.class)`.

Note: cross-instance L1 invalidation is not implemented — an evict clears the local L1 + shared L2; other nodes' L1 entries expire by TTL.

## Key properties
| Property | Default | Purpose |
|---|---|---|
| `acme.cache.two-tier.enabled` | `false` | Register the Caffeine L1 + Redis L2 two-tier `CacheManager` instead of the Caffeine-only one |

## Usage
```kotlin
implementation("acme-bank:acme-cache-spring-boot-starter")
```
On the classpath this activates `CacheAutoConfiguration` and enables Spring caching; add a Redis starter and set `acme.cache.two-tier.enabled=true` for the two-tier manager.

## See also
- ADR-0007 Utility starters: cache (Caffeine), resilience (Resilience4j), feature flags (OpenFeature)
- root README
