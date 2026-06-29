# acme-ratelimit

Makes Bucket4j servlet rate-limiting boot out of the box. Bucket4j's synchronous (servlet) filter resolves buckets through a JSR-107 `CacheManager` that must already hold the caches named by `bucket4j.filters[].cache-name`; without one the context fails to start (`No Bucket4j cache configuration found`). This module supplies that cache backend so any service can rate-limit purely via `bucket4j.*` properties.

## What it configures
- `rateLimitJCacheManager` — a Caffeine-backed JSR-107 `javax.cache.CacheManager` pre-populated with every cache named by the configured Bucket4j filters (plus the filter-config cache when enabled, and the default `buckets` cache). Values are `byte[]` (serialized bucket state). `@ConditionalOnMissingBean(CacheManager.class)`, `destroyMethod = "close"`.
- `bucket4jSyncCacheResolver` — Bucket4j's `SyncCacheResolver` (`JCacheCacheResolver`) backed by the manager above, provided explicitly so the resolver is present regardless of auto-config ordering. `@ConditionalOnMissingBean(SyncCacheResolver.class)`.

Registered by `RateLimitAutoConfiguration` (`@AutoConfiguration(before = Bucket4jCacheConfiguration.class)`), gated on `Caching`/`CaffeineCachingProvider` on the classpath. Buckets are per-replica (each instance keeps its own counters); for buckets shared across replicas, wire a distributed backend and this default steps aside via `@ConditionalOnMissingBean`.

## Key properties
| Property | Default | Purpose |
|---|---|---|
| `bucket4j.enabled` | (unset) | Activates this auto-configuration when `true`. Defined and consumed by `bucket4j-spring-boot-starter`; filters, URLs, bandwidths, and `cache-name` are configured under `bucket4j.*`. |

This module defines no properties of its own; it reads the `bucket4j.*` binding to discover cache names.

## Usage
```kotlin
implementation("acme-bank:acme-ratelimit-spring-boot-starter")
```
With `bucket4j.enabled=true` and one or more `bucket4j.filters`, rate-limiting works with no per-service cache wiring.

## See also
- ADR-0016 REST edge — idempotency filter + Bucket4j rate-limiting
- root README
