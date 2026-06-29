# acme-web

REST edge cross-cutting concerns for servlet web apps: a uniform RFC 9457 problem+json error contract and safe-retry idempotency for unsafe HTTP methods. Both activate automatically on a Spring MVC classpath so every `acme-bank` service shares one error shape and one idempotency story.

## What it configures
- `ProblemExceptionHandler` — `@RestControllerAdvice` (highest precedence) rendering errors as RFC 9457 `ProblemDetail`. Maps `ApiException` (carrying a stable `ErrorCode` + params) to problem+json with a `code`, `type` URI (`https://errors.acme.com/<code>`), `params`, and `traceId` (from MDC) when present. Overrides `handleMethodArgumentNotValid` to emit a unified validation shape: `code: VALIDATION_FAILED` plus a per-field `errors` array. Registered via `AcmeWebAutoConfiguration`, `@ConditionalOnMissingBean`.
- `IdempotencyFilter` — `OncePerRequestFilter` for POST/PATCH/PUT requests carrying an `Idempotency-Key` header. First request reserves the key and executes; a retry replays the stored 2xx response; a concurrent in-progress key returns 409 Conflict. Only 2xx outcomes are cached (4xx/5xx release the reservation).
- `IdempotencyStore` — `InMemoryIdempotencyStore` (Caffeine, 24h TTL, max 100k entries) by default; auto-swaps to `RedisIdempotencyStore` (key `idem:<key>`, `SETNX`+TTL) when a `StringRedisTemplate` is on the context (multi-instance deployments). Both `@ConditionalOnMissingBean(IdempotencyStore.class)`; the idempotency config runs `after = RedisAutoConfiguration`.

Public types for services: `ErrorCode` (interface, implement per service, usually an enum) and `ApiException`.

## Key properties
| Property | Default | Purpose |
|---|---|---|
| `acme.web.problem.enabled` | `true` | Activate the problem+json exception handler |
| `acme.web.idempotency.enabled` | `true` | Register the idempotency filter |

## Usage
```kotlin
implementation("acme-bank:acme-web-spring-boot-starter")
```
On a servlet (`DispatcherServlet`) classpath the problem handler and idempotency filter auto-activate; add a `StringRedisTemplate` bean to make idempotency multi-instance safe.

## See also
- ADR-0016 REST edge — idempotency filter + Bucket4j rate-limiting
- root README
