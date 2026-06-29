# acme-ratelimit

Makes Bucket4j servlet rate-limiting boot out of the box. Bucket4j's synchronous (servlet) filter resolves buckets through a JSR-107 `CacheManager` that must already hold the caches named by `bucket4j.filters[].cache-name`; without one the context fails to start (`No Bucket4j cache configuration found`). This module supplies that cache backend so any service can rate-limit purely via `bucket4j.*` properties.

## What it configures
- `rateLimitJCacheManager` — a Caffeine-backed JSR-107 `javax.cache.CacheManager` pre-populated with every cache named by the configured Bucket4j filters (plus the filter-config cache when enabled, and the default `buckets` cache). Values are `byte[]` (serialized bucket state). `@ConditionalOnMissingBean(CacheManager.class)`, `destroyMethod = "close"`.
- `bucket4jSyncCacheResolver` — Bucket4j's `SyncCacheResolver` (`JCacheCacheResolver`) backed by the manager above, provided explicitly so the resolver is present regardless of auto-config ordering. `@ConditionalOnMissingBean(SyncCacheResolver.class)`.

Registered by `RateLimitAutoConfiguration` (`@AutoConfiguration(before = Bucket4jCacheConfiguration.class)`), gated on `Caching`/`CaffeineCachingProvider` on the classpath **and** `acme.ratelimit.backend` being `local` (the default) or unset.

## Choosing a backend: `acme.ratelimit.backend`

| Value | Backend | Scope of the limit |
|---|---|---|
| `local` (default) | Caffeine-backed JSR-107 cache, in-process | **Per replica.** Each instance keeps its own counters, so a per-caller limit of N effectively allows `N × replicaCount` cluster-wide. Fine for single-instance or best-effort limiting. |
| `redis` | Bucket4j distributed `LettuceBasedProxyManager` over Redis | **Cluster-wide.** All replicas share one bucket per caller key, so the limit is enforced globally regardless of replica count. |

The default stays `local` to preserve existing behavior with zero new dependencies.

### Redis (distributed) backend
Set `acme.ratelimit.backend=redis` and put the Redis backend on the classpath — typically `spring-boot-starter-data-redis` (brings Lettuce) plus `com.bucket4j:bucket4j_jdk17-lettuce`. When active, `RedisRateLimitAutoConfiguration`:
- builds a Lettuce `RedisClient` from the standard `spring.data.redis.*` properties (`@ConditionalOnMissingBean` — supply your own `RedisClient` to reuse an existing connection),
- builds a distributed `LettuceBasedProxyManager` (keys auto-expire once a bucket would refill to full, bounding the keyspace), and
- exposes it to Bucket4j's **servlet** filter as a `SyncCacheResolver` (a `RedisProxyManagerCacheResolver`). The stock `LettuceCacheResolver` the giffing starter ships is an `AsyncCacheResolver` used only by the reactive WebFlux/Gateway filters, so the servlet path needs this sync bridge.

When `backend=redis` the in-process `local` backend steps aside (its `backend=local` condition no longer matches). If `backend=redis` but the Redis classes are absent, `RedisRateLimitMissingDependencyAutoConfiguration` fails startup with a clear message (rather than Bucket4j's opaque "No Bucket4j cache configuration found").

Trade-off: the Redis backend adds a network round-trip (a CAS) to every rate-limited request and couples rate limiting to Redis availability; `local` is faster and self-contained but cannot enforce a global limit. See ADR-0034.

## Key properties
| Property | Default | Purpose |
|---|---|---|
| `bucket4j.enabled` | (unset) | Activates rate-limiting when `true`. Defined and consumed by `bucket4j-spring-boot-starter`; filters, URLs, bandwidths, and `cache-name` are configured under `bucket4j.*`. |
| `acme.ratelimit.backend` | `local` | `local` (in-process, per-replica) or `redis` (distributed, cluster-wide). |
| `acme.ratelimit.redis.bucket-ttl` | `1h` | Redis-only. Max time a bucket needs to refill to full; bucket keys expire after this, keeping the keyspace bounded. MUST be `>=` your longest bandwidth period or state is evicted before the window closes. |

The `local` backend reads the `bucket4j.*` binding to discover cache names; it defines no properties of its own.

## Usage
```kotlin
implementation("acme-bank:acme-ratelimit-spring-boot-starter")
// For the distributed backend, also add (consumer side):
//   implementation("org.springframework.boot:spring-boot-starter-data-redis")
//   implementation("com.bucket4j:bucket4j_jdk17-lettuce:8.14.0")
```
With `bucket4j.enabled=true` and one or more `bucket4j.filters`, rate-limiting works with no per-service cache wiring. Add `acme.ratelimit.backend=redis` (+ Redis on the classpath) for a cluster-wide limit.

## See also
- ADR-0016 REST edge — idempotency filter + Bucket4j rate-limiting
- ADR-0034 distributed rate limit — local-vs-redis trade-off
- root README
