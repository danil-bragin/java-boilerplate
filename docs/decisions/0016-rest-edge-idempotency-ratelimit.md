# ADR 0016: REST edge — idempotency filter + Bucket4j rate-limiting

**Date:** 2026-06-18
**Status:** Accepted

## Context

The `transfers` service exposes `POST /v1/transfers` and `GET /v1/transfers/{id}`. Without guards, clients can replay requests (network retries) and create duplicate transfers, and abusive callers can exhaust DB connections. Two concerns:

1. **Idempotency**: clients must be able to safely retry by attaching `Idempotency-Key`. The response for a repeated key must be byte-identical to the first successful response.
2. **Rate limiting**: at-IP granularity, configurable via properties, zero-overhead when disabled.

Both are cross-cutting concerns reusable across all `acme-bank` services.

## Decision

### Idempotency filter (`acme-web-spring-boot-autoconfigure`)

`IdempotencyFilter` extends `OncePerRequestFilter` and intercepts POST, PATCH, and PUT requests that carry an `Idempotency-Key` header:

- **Cache hit**: returns the stored status code, `Content-Type`, and body directly; the handler is not invoked.
- **Cache miss**: wraps the response in `ContentCachingResponseWrapper`, invokes the handler, then stores the response only if the status is not 5xx (server errors are not idempotent).

The store is abstracted behind `IdempotencyStore` (interface with `find` / `save`). The default implementation is `InMemoryIdempotencyStore` backed by `ConcurrentHashMap`. Both beans are registered conditionally (`@ConditionalOnMissingBean`) so consumers can replace them with a Redis-backed store.

Property `acme.web.idempotency.enabled` (default `true`) controls activation. Property `acme.web.problem.enabled` (default `true`) controls the problem+json error handler activated in the same autoconfiguration.

### Rate limiting (`acme-web-spring-boot-starter`)

`bucket4j-spring-boot-starter` 0.13.0 is bundled as an `api` dependency of `acme-web-spring-boot-starter`. Rate-limit behaviour is entirely property-driven:

```yaml
bucket4j:
  enabled: true
  filters:
    - cache-name: rate-limit-buckets
      url: /v1/.*
      strategy: first
      http-status-code: TOO_MANY_REQUESTS
      rate-limits:
        - cache-key: "@request.remoteAddr"
          bandwidths:
            - capacity: 100
              time: 1
              unit: minutes
              refill-speed: greedy
```

`bucket4j.enabled: false` in test `application.yaml` disables the startup check so tests do not require a cache provider.

### `transfers` REST edge

`TransferController` (adapter in `adapter/in/web`) exposes:
- `POST /v1/transfers` → 202 Accepted `{ transferId, status: "REQUESTED" }`
- `GET /v1/transfers/{id}` → 200 `{ status }`, 404 if not found

Requests require a valid Bearer JWT. The `acme-security-spring-boot-starter` provides `SecurityFilterChain` with OAuth2 JWT resource server wired to `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`.

### Autoconfig ordering — Bucket4j + ServiceConnection conflict

Bucket4j's `Bucket4jCacheConfiguration` carries `@AutoConfigureAfter(CacheAutoConfiguration.class)`. `CacheAutoConfiguration` chains via `@AutoConfiguration(after = HibernateJpaAutoConfiguration.class)` → `DataSourceAutoConfiguration`. This topological change causes `DataSourceAutoConfiguration.jdbcConnectionDetails()` (`@ConditionalOnMissingBean`) to evaluate **before** `ServiceConnectionAutoConfigurationRegistrar` registers `JdbcConnectionDetails` from `@Bean @ServiceConnection` methods. `DataSourceAutoConfiguration` therefore creates `PropertiesJdbcConnectionDetails` from blank properties.

The same ordering impact affects `RedisAutoConfiguration` for Redis connection details.

**Fix**: `PostgresTestcontainersConfiguration` and `RedisTestcontainersConfiguration` in `acme-test-support` now use `DynamicPropertyRegistrar` instead of `@Bean @ServiceConnection`. `DynamicPropertyRegistrar` beans are processed before any autoconfig condition is evaluated, making them immune to ordering changes.

`RedpandaTestcontainersConfiguration` retains `@Bean @ServiceConnection` because `KafkaAutoConfiguration` is not in the affected ordering chain.

### KafkaJacksonConfiguration conflict

`KafkaJacksonConfiguration` (Spring Modulith) activates by default (`matchIfMissing=true`) and loads `kafka-json.properties` via `@PropertySource`, which sets `spring.kafka.consumer.value-deserializer=ByteArrayDeserializer`. This overrides the `StringDeserializer` declared in the main `application.yaml` when the test context's property source ordering resolves `@PropertySource` with higher effective precedence.

The symptom: `DeadLetterPublishingRecoverer` receives `byte[]` from the consumer but the DLT producer is configured with `StringSerializer` → `ClassCastException`.

**Fix**: test `application.yaml` files that run with Kafka explicitly set `spring.kafka.consumer.value-deserializer: org.apache.kafka.common.serialization.StringDeserializer` and `spring.modulith.events.kafka.enable-json: false` where Avro is used.

## Consequences

- Idempotency is handled transparently for all POST/PATCH/PUT endpoints across all services that depend on `acme-web-spring-boot-starter`.
- Rate limiting is zero-config-to-disable and fully property-driven; no code change is needed to tune limits.
- `@Bean @ServiceConnection` is replaced by `DynamicPropertyRegistrar` for datasource and Redis in all test support configurations; future additions should follow the same pattern.
- Integration tests for services using Bucket4j must set `bucket4j.enabled: false` in test `application.yaml`.
- Services using Modulith Kafka with Avro must set `spring.modulith.events.kafka.enable-json: false` and explicitly declare `spring.kafka.consumer.value-deserializer` in test `application.yaml`.
