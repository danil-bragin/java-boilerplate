# ADR 0034: Optional Redis-backed distributed rate limit (`acme-ratelimit`)

**Date:** 2026-06-30
**Status:** Accepted

## Context

`acme-ratelimit` makes Bucket4j servlet rate-limiting boot out of the box by supplying a Caffeine-backed JSR-107 `CacheManager` for the bucket store (ADR-0016). That store is **in-process**: each replica keeps its own counters, so a per-caller limit of N is enforced *per instance*. Behind a load balancer across R replicas the effective limit becomes `N × R` — a caller hitting 5 instances at "100 req/min each" can actually push ~500 req/min. This is a known production gap surfaced during the `acme-bank` scaling work (gateway/transfers run multiple replicas): the rate limit is best-effort, not a real global ceiling.

For an abuse/DoS control or a hard quota, the limit must be **shared across replicas** — one bucket per caller, visible to every instance. That requires a backing store outside the JVM. The platform already runs Redis (the `acme-web` idempotency filter uses Redis `SET NX`; Spring Data Redis / Lettuce is on several classpaths), and Bucket4j ships a distributed `LettuceBasedProxyManager` (`bucket4j-redis`) that stores bucket state in Redis with compare-and-swap. So the building blocks exist; the question is how to offer the distributed backend without disturbing the in-process default or forcing Redis on every consumer.

## Decision

Add a backend selector — `acme.ratelimit.backend` = `local` (default) or `redis` — and a Redis-backed distributed backend alongside the unchanged in-process one.

### `local` stays the default, unchanged
`RateLimitAutoConfiguration` gains `@ConditionalOnProperty(acme.ratelimit.backend = local, matchIfMissing = true)`. With no property set it behaves exactly as before (Caffeine JSR-107, per-replica buckets). Existing services and tests are unaffected — zero new dependencies on the default path.

### `redis` is opt-in and classpath-gated
`RedisRateLimitAutoConfiguration` activates only when `acme.ratelimit.backend=redis`, `bucket4j.enabled=true`, **and** the Lettuce + `bucket4j_jdk17-lettuce` classes are present (`@ConditionalOnClass`). It:
- builds a Lettuce `RedisClient` from the standard `spring.data.redis.*` properties (`@ConditionalOnMissingBean`, so a consumer can supply/share their own client), and
- builds a distributed `LettuceBasedProxyManager` over a `byte[]` connection, with keys auto-expiring once a bucket would refill to full (bounded keyspace), and
- exposes it to Bucket4j's servlet filter as a `SyncCacheResolver`.

The Redis deps are `compileOnly` in the autoconfigure module (and brought by the consumer), so `local` users never pull Redis transitively — matching the optional-slice convention of the other starters.

### A sync bridge, not the stock async resolver
Bucket4j's **servlet** filter resolves buckets through a `SyncCacheResolver`. The `LettuceCacheResolver` the giffing starter ships is an `AsyncCacheResolver`, used only by the reactive WebFlux/Gateway filters — invisible to the servlet filter. So the Redis backend provides `RedisProxyManagerCacheResolver`, a `SyncCacheResolver` that drives the same distributed Lettuce `ProxyManager` through Bucket4j's synchronous API. This is the load-bearing piece that makes a servlet app enforce a cluster-wide limit.

### Fail fast on misconfiguration
If `backend=redis` but the Redis classes are absent, the local backend has already stepped aside and the Redis backend cannot activate, so Bucket4j would otherwise abort with the opaque "No Bucket4j cache configuration found". `RedisRateLimitMissingDependencyAutoConfiguration` turns that into a clear, actionable startup error naming the missing dependency.

### `bucket-ttl` is validated at startup (security-critical)
Bucket keys auto-expire after `acme.ratelimit.redis.bucket-ttl`. If that is shorter than the longest configured `bucket4j.filters[].rate-limits[].bandwidths[]` period, Redis evicts the bucket state *before the window closes* and the limit silently resets — a caller can then exceed the intended ceiling. `RedisRateLimitAutoConfiguration` reads the configured bandwidth periods, computes the longest, and **fails startup** (`IllegalStateException`) if `bucket-ttl` is smaller; when no bandwidths are readable it logs the constraint loudly instead. The property also carries `@Validated` + `@NotNull` and must be positive. Default is `1h`.

### Redis-outage fail policy is explicit (`acme.ratelimit.redis.fail-open`)
The synchronous Redis CAS backing each decision can throw when Redis is unreachable/slow. Left unhandled, that exception bubbles through Bucket4j's servlet filter to the caller as a raw HTTP 500 — a rate-limiter outage becoming a self-inflicted API outage, with no log, metric, or defined policy. The backend gives this an explicit, documented behaviour:

- **`true` (default — fail-open):** allow the request through so a Redis outage cannot take down the whole API. The limit is not enforced for that request; the outage is visible via a WARN log and the `acme.ratelimit.redis.errors{outcome=allowed}` Micrometer counter.
- **`false` (fail-closed):** reject with a clean **HTTP 503** (+ `Retry-After`), never a raw 500; also logged and metered (`outcome=rejected`).

The interception sits at the closest reachable point to the failed Redis op: `RedisProxyManagerCacheResolver` overrides Bucket4j's sync `resolve(...)` and wraps the `ProxyManagerWrapper`, catching the exception thrown while building the bucket or running `tryConsumeAndReturnRemaining`. Fail-open returns a synthetic "allowed" result there; fail-closed throws `RedisRateLimitUnavailableException`, which an outermost servlet guard (`RedisRateLimitOutageFilter`, ordered `HIGHEST_PRECEDENCE`, outside Bucket4j's `HIGHEST_PRECEDENCE + 10` filter) converts to the 503. The metric is gated on a `MeterRegistry` bean via `ObjectProvider`, so Micrometer stays optional. This covers the **servlet** (synchronous) path — the only path the Redis backend wires.

### The built-in client is single-node only; bring your own for more
`rateLimitRedisClient` honours exactly `spring.data.redis.{host,port,ssl.enabled,username,password,database}` and intentionally ignores `timeout`/pool/`sentinel.*`/`cluster.*`; it does not reuse a Spring Data Redis `LettuceConnectionFactory`. For timeouts/pooling, Sentinel/Cluster, or to reuse an existing connection, supply your own `RedisClient` `@Bean` (`@ConditionalOnMissingBean` lets it win). This is documented on the bean and in the README rather than expanded in code, keeping the default path a minimal single-node client.

## Consequences

- **Correct global limiting when wanted.** Flip one property (+ Redis on the classpath) and a per-caller limit is enforced cluster-wide, independent of replica count. Proven by an integration test: two independently-built ProxyManagers (two simulated replicas) against the same Redis share one limit — with limit 5, exactly 5 of 6 requests pass, not 10.
- **Latency / availability trade-off.** The distributed backend adds a Redis round-trip (a CAS) to every rate-limited request and couples rate limiting to Redis availability. `local` is faster and self-contained but cannot enforce a global ceiling. Choosing `local` by default keeps the fast, dependency-free path the norm and makes the network/availability cost an explicit opt-in. The `fail-open` default keeps that availability coupling *soft* — a Redis outage degrades to "limit not enforced" rather than "API down" — while `fail-closed` is available for hard-quota paths that must reject when the limiter cannot decide.
- **One Redis/Lettuce client, reused.** The backend builds its client from the same `spring.data.redis.*` config the rest of the platform uses, and backs off to a consumer-supplied `RedisClient` — no second client library, no duplicate connection config.
- **`acme-bank` left untouched.** This ADR adds the capability to the starter; switching the bank's gateway/transfers to `backend=redis` is a separate, opt-in change.
- **Keyspace caveat.** All redis-backed filters share one Redis keyspace keyed by the per-caller rate-limit key (mirrors the giffing `LettuceCacheResolver`'s own behavior); bucket keys auto-expire via `bucket-ttl`, which must be `>=` the longest bandwidth period.

## See also
- ADR-0016 REST edge — idempotency filter + Bucket4j rate-limiting (the in-process baseline)
- ADR-0007 Utility starters — the optional-slice / `compileOnly` convention this follows
- `acme-ratelimit-spring-boot-autoconfigure/README.md`
