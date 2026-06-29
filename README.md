# acme-boilerplate

[![CI](https://github.com/danil-bragin/java-boilerplate/actions/workflows/ci.yml/badge.svg)](https://github.com/danil-bragin/java-boilerplate/actions/workflows/ci.yml)
[![CodeQL](https://github.com/danil-bragin/java-boilerplate/actions/workflows/codeql.yml/badge.svg)](https://github.com/danil-bragin/java-boilerplate/actions/workflows/codeql.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-blue.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen.svg)](https://spring.io/projects/spring-boot)

A production-grade, opinionated Spring Boot 3.5 / Java 21 microservice boilerplate. It ships as a set of reusable `acme-*-spring-boot-starter` modules that bring cross-cutting concerns (REST problem details, persistence, observability, CQRS, outbox/inbox, security, caching, resilience, feature flags) onto the classpath via Spring Boot auto-configuration. A deletable `examples/demo-service` wires all starters together and proves each with integration tests. Oracle is the primary/reference database; Postgres is a supported swap — the reusable layer stays DB-agnostic throughout.

---

## Quickstart

**Prerequisites:** JDK 21, Docker (for Testcontainers and the local stack).

```bash
# On a fresh clone the wrapper downloads the Gradle distribution (needs network once).
# Tests start Postgres and Redpanda automatically via Testcontainers — no manual setup required.
./gradlew build
```

To run the demo service against the full local stack:

```bash
# Start backing infrastructure (Postgres, Redpanda, Keycloak, OTel/Grafana)
docker compose up -d

# Run the demo
./gradlew :examples:demo-service:bootRun
```

---

## Repo layout

```
acme-boilerplate/                    Gradle monorepo (Kotlin DSL)
├── settings.gradle.kts
├── gradle/
│   └── libs.versions.toml           version catalog (Spring Boot 3.5.6, Modulith 1.4.6, …)
├── build-logic/                     included build — convention plugins
│   └── src/main/kotlin/
│       └── acme.java-conventions.gradle.kts   (JDK 21 toolchain, Spotless palantir, JUnit 5)
├── platform/
│   └── acme-bom/                    java-platform BOM — re-exports spring-boot-dependencies
│                                    + spring-modulith-bom; pin it in any consumer
├── starters/                        reusable cross-cutting modules (zero business logic)
│   ├── acme-web-spring-boot-{autoconfigure,starter}
│   ├── acme-persistence-spring-boot-{autoconfigure,starter}
│   ├── acme-observability-spring-boot-{autoconfigure,starter}
│   ├── acme-cqrs-spring-boot-{autoconfigure,starter}
│   ├── acme-outbox-spring-boot-starter
│   ├── acme-messaging-spring-boot-{autoconfigure,starter}
│   ├── acme-security-spring-boot-{autoconfigure,starter}
│   ├── acme-cache-spring-boot-{autoconfigure,starter}
│   ├── acme-resilience-spring-boot-starter
│   ├── acme-featureflags-spring-boot-{autoconfigure,starter}
│   └── acme-test-support            Testcontainers @ServiceConnection config + base ITs
├── examples/
│   └── demo-service/                deletable demo wiring all starters; 26 integration tests
├── compose.yaml                     local backing stack (Postgres, Redpanda, Keycloak, otel-lgtm)
└── docs/
    ├── decisions/                   ADRs 0000–0008 (MADR format)
    └── superpowers/specs/           design spec
```

---

## Starters

Each starter follows the standard Spring Boot pattern: an `*-autoconfigure` module (containing `@AutoConfiguration` classes and `META-INF/spring/…AutoConfiguration.imports`) paired with a thin `*-starter` that pulls in runtime dependencies. Every bean is guarded with `@ConditionalOnMissingBean` and `@ConditionalOnProperty` for easy override or disable.

| Starter | Purpose |
|---|---|
| `acme-web` | RFC 9457 Problem+JSON error handler (`@RestControllerAdvice`), unified validation error shape, i18n via `MessageSource`, idempotency-key filter (Redis `SET NX`), rate limiting (Bucket4j), CORS + security headers, springdoc/OpenAPI |
| `acme-persistence` | Spring Data JPA / Hibernate base config, HikariCP, `@EnableJpaAuditing` with `Clock`-backed `DateTimeProvider`, Flyway with vendor-specific migration dirs (`db/migration/{oracle,postgresql}`), `GenerationType.SEQUENCE` for Oracle-first portability |
| `acme-observability` | Micrometer + OTel bridge → OTLP exporter, Prometheus endpoint, native structured logging (ECS), MDC request-id filter, Actuator liveness/readiness probes, ShedLock JDBC (`usingDbTime()`), `Clock.systemUTC()` bean, graceful shutdown |
| `acme-cqrs` | PipelinR command/query bus; ships `ValidationMiddleware` (`@Order` 10) + `TransactionMiddleware` (`@Order` 20, where the `StronglyConsistent` marker gates `TransactionTemplate` wrapping); logging/metrics middleware are consumer-supplied; jMolecules vocabulary |
| `acme-outbox` | Spring Modulith event publication registry + Kafka externalization (`@Externalized`); at-least-once delivery; Flyway owns the `event_publication` table (Oracle 19c compatible) |
| `acme-messaging` | Spring Kafka consumer base config (Avro/SR-ready deserializer wrapper), JDBC inbox dedup (`processed_messages` unique constraint in the same transaction), DLT error handler |
| `acme-security` | OAuth2 JWT resource server, Keycloak `realm_access.roles` → `ROLE_*` converter, `@EnableMethodSecurity` + `@PreAuthorize` RBAC, Hibernate Envers audit, `AuditorAware` |
| `acme-cache` | Caffeine L1 cache via Spring `@Cacheable` abstraction |
| `acme-resilience` | Resilience4j presets (Retry, CircuitBreaker, TimeLimiter, Bulkhead) + Micrometer metrics |
| `acme-featureflags` | OpenFeature Java SDK; `NoOpProvider` default via `@ConditionalOnMissingBean` (drop in flagd or another provider to override) |
| `acme-test-support` | Shared Testcontainers config — Redpanda via `@ServiceConnection`, Postgres + Redis via `DynamicPropertyRegistrar`; base integration test classes |

---

## How to use as a template

1. Fork this repository.
2. Delete `examples/` — it is a demo only and has no production value.
3. Rename the `acme` prefix to your own artifact group throughout `settings.gradle.kts`, `libs.versions.toml`, and starter module directories.
4. Add `platform:acme-bom` as your BOM, then declare only the starters you need as `implementation` dependencies.
5. Write your domain in your own service module — the starters auto-configure on the classpath.

---

## Local stack

`docker compose up -d` (using `compose.yaml` at the repo root) starts all backing infrastructure:

| Service | Image | Host port(s) | Notes |
|---|---|---|---|
| Postgres | `postgres:16-alpine` | 5432 | DB=demo, user=demo, password=demo |
| Redpanda | `redpandadata/redpanda:v24.2.7` | 9092 (Kafka), 8081 (Schema Registry) | Single-node, no ZooKeeper |
| Redpanda Console | `redpandadata/console:v3.7.4` | 8080 | Topic browser UI |
| Keycloak | `quay.io/keycloak/keycloak:26.2` | 8082→8080 | admin/admin; `start-dev`; JWKS at `http://localhost:8082/realms/<realm>/protocol/openid-connect/certs` |
| Grafana / otel-lgtm | `grafana/otel-lgtm:latest` | 3000 (Grafana), 4317 (OTLP gRPC), 4318 (OTLP HTTP) | All-in-one OTel + Loki + Grafana + Tempo |

Point your service at:
- `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317`
- `spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8082/realms/<your-realm>`

---

## Architecture

### Event choreography

```
Client → POST /v1/orders (REST, Idempotency-Key)
  → CQRS: CreateOrder command dispatched via PipelinR bus
  → JPA INSERT + Spring Modulith outbox (event_publication row in same tx)
  → Modulith externalizes OrderCreated → Kafka topic
  → Consumer (inbox dedup: processed_messages unique constraint)
  → Projection update / downstream command
```

Reads come from projections (read models); there is no synchronous RPC between services. Trace and principal headers travel with Kafka events for full distributed trace continuity.

### Effectively-once delivery

Outbox (at-least-once) + inbox dedup (idempotent consume via `processed_messages` unique constraint in the consumer's own transaction) = effectively-once end-to-end. This does **not** use Kafka EOS/XA — those are Kafka-only and not atomic with a DB write.

### Security

OAuth2 JWT resource server. Keycloak issues tokens; the auto-config maps `realm_access.roles` to Spring Security `ROLE_*` authorities. Method-level RBAC via `@PreAuthorize`. Principal identity travels with async events via Kafka headers (not bearer tokens).

---

## Testing

- **Unit:** JUnit 5 + AssertJ + Mockito
- **Integration:** Spring Boot Testcontainers `@ServiceConnection` — Postgres and Redpanda start automatically; no manual Docker management in tests
- **Async assertions:** Awaitility
- **Gate:** `./gradlew build` (or `gradle build` with system Gradle 8.14) — runs compile, Spotless format check, and all tests

The demo service ships 26 integration tests covering every starter's key behavior.

---

## Architectural Decision Records

ADRs live in [`docs/decisions/`](docs/decisions/) in [MADR](https://adr.github.io/madr/) format. **0000–0008** cover the boilerplate/starters; **0009–0032** cover the `acme-bank` example (saga choreography, money-safety, and the throughput work).

| ADR | Topic |
|---|---|
| 0000 | Use MADR |
| 0001 | Stack and monorepo layout |
| 0002 | Persistence: JPA, Oracle-first |
| 0003 | Observability: OTel, ShedLock |
| 0004 | CQRS: PipelinR |
| 0005 | Outbox: Spring Modulith + Kafka |
| 0006 | Security: OAuth2 + Keycloak |
| 0007 | Utility starters (cache, resilience, feature flags) |
| 0008 | Inbox: effectively-once |
| 0009–0032 | `acme-bank`: double-entry ledger, idempotency, reconciliation, gateway/scaling, synchronous fast-path (see [`docs/decisions/`](docs/decisions/)) |

Full design rationale: [`docs/superpowers/specs/2026-06-17-acme-boilerplate-design.md`](docs/superpowers/specs/2026-06-17-acme-boilerplate-design.md)

---

## License

Released under the [MIT License](LICENSE).
