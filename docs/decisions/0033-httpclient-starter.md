# ADR 0033: Declarative outbound HTTP clients (`acme-httpclient`) — resilience, token relay, observation baked in

**Date:** 2026-06-29
**Status:** Accepted

## Context

Every microservice in the platform calls other services. Today `acme-bank` hand-rolls this: the gateway and `transfers` each write their own `RestClientConfig`, repeat the bearer-token-relay interceptor, re-derive connect/read timeouts, and wrap every call site in a Resilience4j-annotated delegating `@Component`. The mechanics are identical across services and easy to get subtly wrong — and getting them wrong is expensive:

- **Missing token relay** → the downstream OAuth2 resource server returns 401, which a resilience fallback then masks as a 503 (this exact bug blocked the live e2e until the gateway added a relay interceptor).
- **Unbounded timeouts** → a slow downstream pins HTTP connections (and, on the synchronous fast-path, a DB connection inside the transaction).
- **No observation wiring** → outbound calls are invisible to distributed tracing and metrics.

These are cross-cutting concerns reusable across all services — the same shape as the other utility starters (`acme-cache`, `acme-resilience`, `acme-security`). They belong in a starter, not copied per service.

## Decision

Introduce the `acme-httpclient` starter pair (`-autoconfigure` + thin `-starter`) providing **one** opinionated, auto-configured way to build typed outbound HTTP clients.

### Declarative interface clients over hand-rolled `RestClient`

Consumers define a typed `@HttpExchange` interface and obtain a proxy from a `HttpClients` factory bean (Spring's interface-client support: `HttpServiceProxyFactory` over a `RestClientAdapter`). The call sites become typed method calls instead of fluent `RestClient` chains scattered through adapters. The proxy is backed by the shared, auto-configured `RestClient.Builder`, so it inherits everything below for free.

### Resilience + token relay + observation baked in

- **Observation** — the shared `RestClient.Builder` is wired with the `ObservationRegistry` when one is present, so outbound calls automatically become traced + metered observations (`@ConditionalOnBean`-style optional via `ObjectProvider`, no hard dependency).
- **Timeouts** — connect/read timeouts come from `acme.httpclient.*` with sane defaults (2s / 10s), applied through `ClientHttpRequestFactorySettings` exactly as the `transfers` fast-path does.
- **Token relay** — the gateway's `BearerTokenRelayInterceptor` pattern is contributed as a `RestClientCustomizer`, gated `acme.httpclient.token-relay.enabled=true` **and** `@ConditionalOnClass(JwtAuthenticationToken.class)` so it stays optional and backs off cleanly. **Security boundary (hardened vs. the gateway original):** a bearer token is the caller's identity, so relay is restricted to an explicit allow-list of internal hosts (`acme.httpclient.token-relay.allowed-hosts`, exact or `*.` wildcard). The list is **fail-safe** — empty relays to nothing even when enabled, so turning relay on without naming trusted hosts cannot leak the token to a third-party host. A negative test asserts no relay to an off-list host.
- **Resilience** — a `ResilienceDecorator` wraps a blocking call with a Resilience4j `CircuitBreaker` + `Retry` resolved by instance name, reusing the registries and presets brought by `acme-resilience` (configured under `resilience4j.*`). Gated `@ConditionalOnClass(CircuitBreakerRegistry.class)`. The starter's `readTimeout` bounds per-call latency, so a programmatic `TimeLimiter` is unnecessary for synchronous calls; the README documents the annotation pattern (`@CircuitBreaker`/`@Retry`/`@TimeLimiter` on a delegating component) for callers who want the full triad — the same pattern the gateway already uses.

### Why a starter

Every bean is guarded with `@ConditionalOnMissingBean` / `@ConditionalOnClass` / `@ConditionalOnProperty`, registered via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. The optional slices (security, resilience) are `compileOnly` in the autoconfigure module and brought by the consumer — the autoconfig backs off when they are absent. This matches the platform convention: an `-autoconfigure` module of `@AutoConfiguration` classes paired with a thin `-starter` that pulls runtime deps.

## Consequences

- New services get resilient, observable, auth-propagating HTTP clients by adding one dependency and defining an interface — no copied `RestClientConfig`.
- `acme-bank`'s hand-rolled clients are intentionally **left untouched** (this ADR adds the reusable starter; migrating the bank is a separate, optional step).
- Tests are container-free (`@RestClientTest`-style `MockRestServiceServer` + `ApplicationContextRunner`): a declarative client GET+deserialize, the token-relay interceptor adding/omitting the bearer, and the builder being observation-wired.

## See also
- ADR-0007 Utility starters (cache, resilience, feature flags) — the convention this follows
- ADR-0016 REST edge (idempotency + rate-limiting) — sibling cross-cutting web concerns
- `acme-security`, `acme-resilience`, `acme-observability`
