# acme-boilerplate — Design

> Spring Boot microservice boilerplate. Analogue of go-boilerplate, built on ready-made Spring solutions.
> Status: brainstorm complete; decisions + library choices fixed via deep research (6 agents, 2026-06-17).
> Baseline: **Spring Boot 3.5.x / Java 21 LTS / Gradle 8.x–9.x (Kotlin DSL)**.
> Date: 2026-06-17.

---

## 1. Goal

A donor template for new services: a reusable starter layer + deletable example services + Kafka event choreography.
Usage workflow: fork → delete `examples/` → add the needed starters as dependencies → write your domain, reusing ready-made building blocks (auth, kafka, outbox, observability, cqrs).

NOT a 1:1 port of go-boilerplate. We take the ideas (CQRS, outbox/inbox, choreography, multi-tenancy, observability) and implement them idiomatically for Spring using existing libraries/starters.

---

## 2. Fixed decisions

| # | Decision | Choice | Rationale |
|---|---|---|---|
| Architecture | shape | **Multi-service choreography**: starter layer + example services + Kafka | close to the Go version, but on Spring building blocks |
| Packaging | reusable layer | **Custom Spring Boot Starters** (`-autoconfigure` + thin `-starter`) | idiomatic Spring way, auto-config on classpath |
| Naming | umbrella | `starters/` directory, modules `acme-*-spring-boot-starter`, BOM `acme-bom` | "platform" in Java = BOM, not code; name collision |
| Prefix | artifact | `acme` (placeholder) | public template, replaced with your own |
| Build | tool | **Gradle monorepo** + `build-logic` convention plugins + `libs.versions.toml` + `java-platform` BOM | large monorepo, build perf, config cache |
| A | persistence | **Spring Data JPA / Hibernate** | enterprise standard, DB portability, talent pool |
| DB | default | **Oracle Database = primary/reference**, **Postgres swappable**; reusable layer stays DB-agnostic | target enterprise infra is Oracle; portability via JPA |
| B | event contracts | **Avro + Confluent Schema Registry** | Kafka-world standard, enforced schema compatibility |
| C | CQRS | **Lightweight**: PipelinR command/query bus + explicit middleware pipeline | spirit of Go's typed decorators, Spring-native, easy to remove |
| Scope | breadth | **everything in scope** (retry/DLT, rate-limit, i18n, audit, idempotency, clock, ADR, CI, containers) | full enterprise template |

---

## 3. Stack (final, with versions)

| Concern | Spring solution | Artifact / version (Boot 3.5 BOM where possible) |
|---|---|---|
| Lang/runtime | Java 21 LTS + Spring Boot 3.5.x | Framework 6.2.x, Security 6.5.x |
| HTTP edge | Web MVC + virtual threads + springdoc | `springdoc-openapi-starter-webmvc-ui:2.8.17` (≥2.8.13!) |
| Kafka | Spring Kafka | `spring-kafka:3.3.x` + `kafka-clients:3.9.x` |
| Event contracts | Avro + Confluent SR | `io.confluent:kafka-avro-serializer:7.9.x` (Confluent maven repo) |
| ORM/queries | Spring Data JPA / Hibernate | Boot BOM; HikariCP; driver `com.oracle.database.jdbc:ojdbc11` (Oracle) / pg (swap) |
| Migrations | Flyway (owns all DDL) | Boot BOM; vendor dirs `db/migration/{oracle,postgresql}`, Oracle = reference |
| Outbox | **Spring Modulith** event registry + Kafka externalization | `spring-modulith-bom:1.4.x` (**pin it yourself** — not in Boot BOM) |
| Inbox dedup | hand-rolled `processed_messages` (unique constraint, same tx) | — |
| Retry/DLT | `@RetryableTopic` (non-blocking) / `DefaultErrorHandler`+`DeadLetterPublishingRecoverer` (blocking, ordering) | spring-kafka |
| Leader/scheduling | **ShedLock** JDBC `usingDbTime()` (DB-portable) | `shedlock-spring`+`shedlock-provider-jdbc-template:7.7.0` |
| Config | `@ConfigurationProperties` + `@Validated` (fail-fast) | `spring-boot-starter-validation` (Hibernate Validator 8.0.3) |
| Logging | **native structured logging** (Boot 3.4+) `logging.structured.format`, MDC | built-in — no logstash-encoder |
| L1 cache | Caffeine | Boot BOM |
| L2 cache | Spring Data Redis (Lettuce) | Boot BOM |
| Object storage | Spring Cloud AWS S3 + MinIO dev | awspring |
| Resilience | Resilience4j | `resilience4j-spring-boot3` |
| Observability | Micrometer + Micrometer Tracing **OTel bridge** → OTLP | `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp`, `micrometer-registry-otlp`, `-prometheus` |
| Auth | OAuth2 Resource Server + Keycloak | `spring-boot-starter-oauth2-resource-server` |
| AuthZ (RBAC) | `@EnableMethodSecurity` + `@PreAuthorize` + custom `PermissionEvaluator` | Security 6.5 |
| Audit | Hibernate Envers (history) + optional hash-chain append-only table | `hibernate-envers` |
| Idempotency (REST) | `Idempotency-Key` filter, Redis `SET NX` + fingerprint | hand-rolled / `spring-idempotency-kit` |
| Rate limiting | Bucket4j (in-mem + Redis) | `bucket4j-spring-boot-starter:0.13.0` (**not 0.14.x = Boot 4**) |
| i18n | `MessageSource` + `AcceptHeaderLocaleResolver`, problem+json localization | built-in |
| Feature flags | OpenFeature Java SDK + flagd | `dev.openfeature` |
| Clock | `java.time.Clock` bean (`systemUTC`), fixed in tests | JDK 21 |
| Testing | JUnit5 + AssertJ + Mockito + Testcontainers `@ServiceConnection` + Awaitility | `spring-boot-testcontainers`, TC 2.0.x |
| Container | `bootBuildImage` (Paketo) + CDS; Jib if no Docker daemon | Boot 3.5 |
| Local stack | `spring-boot-docker-compose` + `compose.yaml` | Boot 3.1+ |
| CI | GitHub Actions `setup-gradle@v6` + Spotless(format)+Checkstyle(semantic) + Renovate | — |
| CQRS bus | **PipelinR** + explicit middleware; jMolecules as vocabulary+ArchUnit | `net.sizovs:pipelinr:0.11`, `jmolecules-bom:2025.0.2` |
| ADR | MADR template in `docs/decisions/` | MADR 4.0.0 |

---

## 4. Repository layout

```
acme-boilerplate/                    Gradle monorepo (Kotlin DSL)
├── settings.gradle.kts
├── gradle/libs.versions.toml         version catalog (own build)
├── build-logic/                      included build = convention plugins
│   └── src/main/kotlin/
│       ├── acme.java-conventions.gradle.kts        (toolchain 21, -parameters, JUnit5, Spotless)
│       └── acme.starter-conventions.gradle.kts
├── platform/
│   └── acme-bom/                      java-platform — versions for consumers (re-export spring-boot-dependencies)
├── starters/                         reusable cross-cutting (zero business logic)
│   ├── acme-web-spring-boot-starter / -autoconfigure
│   ├── acme-observability-spring-boot-starter
│   ├── acme-security-spring-boot-starter
│   ├── acme-messaging-spring-boot-starter
│   ├── acme-outbox-spring-boot-starter
│   ├── acme-cqrs-spring-boot-starter
│   ├── acme-persistence-spring-boot-starter
│   ├── acme-cache-spring-boot-starter
│   ├── acme-resilience-spring-boot-starter
│   ├── acme-featureflags-spring-boot-starter
│   └── acme-test-support            (testFixtures: Testcontainers @ServiceConnection config, base ITs)
├── examples/                         deletable demo services
│   ├── gateway/   orders/   payments/   notifications/
├── compose.yaml                      local stack (Oracle, Redpanda, Redis, Keycloak, otel-lgtm)
└── docs/decisions/                   ADR (MADR)
```

**Starter mechanics** (each): two modules — `*-autoconfigure` (`@AutoConfiguration` classes, `@ConfigurationProperties`, `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, configuration-processor; optional deps as `compileOnly`/`optional`) + thin `*-starter` (depends on autoconfigure + runtime libs). Every `@Bean` guarded with `@ConditionalOnMissingBean`, toggled via `@ConditionalOnProperty`. Never prefix with `spring-boot-`.

---

## 5. Starters — detailed

### acme-web-spring-boot-starter
- **Problem+json (RFC 9457)**: `@RestControllerAdvice extends ResponseEntityExceptionHandler`, `@Order(HIGHEST_PRECEDENCE)`; shared error-code model (stable UPPER_SNAKE `code` + `params` via `ProblemDetail.setProperty`); `type` URI; `traceId` from MDC. Do NOT set `spring.mvc.problemdetails.enabled` — own advice owns the built-ins.
- **Validation**: Jakarta `@Valid`; override `handleMethodArgumentNotValid` + `handleHandlerMethodValidationException` → unified `errors[]` shape; **remove class-level `@Validated` from controllers** (otherwise `ConstraintViolationException`→500). Drop trailing `BindingResult` param.
- **i18n**: `ErrorResponse` + `MessageSource` (`problemDetail.<FQN>`, `problemDetail.title.<FQN>`); `AcceptHeaderLocaleResolver` with `supportedLocales`+fallback. `code`/`params` stay locale-independent.
- **Idempotency**: `OncePerRequestFilter` on POST/PATCH; `Idempotency-Key` → fingerprint = hash(method+path+body); Redis `SET idem:{key} NX EX`; 409 in-progress, replay completed, 422 on mismatch. Short TTL on lock, longer on response. Test the 409 path (virtual threads ↑ concurrency).
- **Rate limiting**: Bucket4j `0.13.0`; servlet filter; key = API-key|IP; Redis backend for shared buckets; fail-open (custom CacheResolver wrapper); 429 + `RateLimit-*` headers.
- **CORS + headers**: `CorsConfigurationSource` with `allowedOriginPatterns` (never `*` with credentials); Security `HeadersConfigurer` — CSP, HSTS (HTTPS-only), Referrer-Policy, Permissions-Policy, COOP. CORS before auth.
- **springdoc**: `2.8.17`; permit `/v3/api-docs/**`,`/swagger-ui/**`; OAuth2 authorizationCode + PKCE.
- **API versioning**: native NOT available on 3.5 (Framework 7/Boot 4 only) → hand-roll `/v1` path (or header `RequestCondition`). Door open to Boot 4 native.

### acme-observability-spring-boot-starter
- Micrometer + **Micrometer Tracing OTel bridge** → OTLP to OTel Collector; Prometheus actuator endpoint secondary.
- Deps: `spring-boot-starter-actuator`, `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp`, `micrometer-registry-otlp`, `micrometer-registry-prometheus`.
- Props (3.5!): `management.otlp.tracing.endpoint`, `management.tracing.sampling.probability`. ⚠️ Boot 4 renames these → do not copy from the Nov-2025 blog.
- **Kafka trace continuity**: `setObservationEnabled(true)` on `KafkaTemplate` + container; `traceparent` header. Batch listeners → `recordObservationsInBatch=true`; async hand-off loses context → `ContextSnapshot`.
- **Native structured logging** (`logging.structured.format.console=ecs`); traceId/spanId auto-injected; custom MDC (requestId) via filter, clear in `finally`. Baggage for cross-service fields.
- **Health**: Actuator probes; liveness MINIMAL (no external deps — else restart-loop); readiness includes db/kafka; programmatic gating via `ApplicationAvailability`.
- **Graceful shutdown**: `server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase=30s`; K8s preStop sleep before SIGTERM; readiness flip first. `terminationGracePeriodSeconds ≥ preStop + drain + headroom`.
- **Config fail-fast**: `@Validated @ConfigurationProperties` + `@Valid` on nested objects.
- **ShedLock** `usingDbTime()` for `@Scheduled` dedup across replicas (DB-portable).
- **Clock** bean (`Clock.systemUTC()`, `@ConditionalOnMissingBean`); `Clock.fixed` in tests.

### acme-messaging-spring-boot-starter
- **Spring Kafka + Avro/SR**: `auto.register.schemas=false` (prod), `use.latest.version=true`, `specific.avro.reader=true`. `ErrorHandlingDeserializer` wraps the Avro deserializer → bad payload to DLT, doesn't poison the partition.
- **Retry/DLT**: `@RetryableTopic` (non-blocking, long backoff, ordering NOT critical) OR `DefaultErrorHandler`+`ExponentialBackOffWithMaxRetries`+`DeadLetterPublishingRecoverer` (blocking, **ordering critical**, EOS-compatible). `@RetryableTopic` is incompatible with batch listeners and EOS transactions, and **breaks per-key ordering**.
- **Inbox dedup**: `processed_messages(message_id PK)` inserted in the same tx as the side effect; duplicate → unique violation → skip. Offset commit ≠ effect applied → inbox needed.
- **Trace/principal headers**: inject on produce, extract on consume.
- **Graceful consumer drain**: `KafkaListenerEndpointRegistry.stop()` (SmartLifecycle); listener containers do NOT react to `server.shutdown=graceful` — separate lifecycle; keep `max.poll.records` 10–50 to drain fast.

### acme-outbox-spring-boot-starter
- **Spring Modulith** event registry: `@ApplicationModuleListener` (= `@Async`+`@Transactional(REQUIRES_NEW)`+`@TransactionalEventListener`); `event_publication` row in the business tx → **at-least-once**; `@Externalized("topic::#{key}")` for Kafka.
- Deps: `spring-modulith-starter-jpa` (or `-jdbc`), `-events-jackson`, `-events-kafka`; **pin `spring-modulith-bom:1.4.x` yourself**.
- `completion-mode=DELETE|ARCHIVE`; `republish-outstanding-events-on-restart` (**single-instance only** — else duplicates; for multi-instance, dedup on consumer + ShedLock).
- **Oracle (primary!)**: Modulith DDL is portable (`schema-oracle.sql`), BUT auto-init uses `CREATE TABLE IF NOT EXISTS` → requires Oracle 23c+. Default targets Oracle 19c (enterprise LTS) → **`spring.modulith.events.jdbc.schema-initialization.enabled=false` + Flyway owns the table** (also the prod practice). `SERIALIZED_EVENT` `VARCHAR2(4000)`→**`CLOB`** for large events — set in our migration.
- **Effectively-once end-to-end** = outbox + inbox (NOT Kafka EOS — EOS is Kafka-only, not atomic with a DB write; avoid XA).

### acme-cqrs-spring-boot-starter
- **PipelinR** (`net.sizovs:pipelinr` — ⚠️ the old `an.awesome:pipelinr` was removed from Central in 2024): types `Command<R>`/`Query<R>` + `Command.Handler<C,R>` + `Command.Middleware`; auto-configure a `Pipelinr` bean from `ObjectProvider` of handlers + ordered middleware.
- **Middleware pipeline** (explicit, not AOP — avoids the self-invocation trap), order outer→inner: **Logging → Metrics → Validation → Transaction → handler**. Two routing pipelines: queries skip Transaction/Audit.
- **Validation middleware**: `jakarta.validation.Validator` before the handler → `ConstraintViolationException` (+ advice → 400).
- **Consistency policy**: marker `StronglyConsistent` on the command; the Transaction middleware wraps only marked commands in a `TransactionTemplate` (distinct template beans per policy, never mutate a singleton); eventual = no tx, domain events via outbox + `@TransactionalEventListener(AFTER_COMMIT, REQUIRES_NEW)`.
- **jMolecules** (`jmolecules-cqrs-architecture`+`-ddd`+`-archunit`, BOM `2025.0.2`) — vocabulary + CI ArchUnit guardrails, NOT a dispatcher.
- If hand-rolling instead of PipelinR — registry-at-startup: `AopUtils.getTargetClass()` (unwrap the proxy!) + `GenericTypeResolver`, fail-fast on duplicate/missing.

### acme-security-spring-boot-starter
- **OAuth2 Resource Server + Keycloak**: `spring-boot-starter-oauth2-resource-server`; `spring.security.oauth2.resourceserver.jwt.issuer-uri=<keycloak realm>`; JWKS auto-refresh; validate issuer/audience, clock skew.
- **Roles→authorities**: `JwtAuthenticationConverter` + a custom `Converter` mapping Keycloak `realm_access.roles` / `resource_access.<client>.roles` → `ROLE_*` / `SCOPE_*`.
- **RBAC**: `@EnableMethodSecurity`; `@PreAuthorize("hasRole(...)")` / authority-based; resource-ownership via custom `PermissionEvaluator` (`@PostAuthorize`).
- **Principal over Kafka**: events carry lineage headers (subject, roles claim), not the full bearer token (async choreography, not sync HTTP).
- **Audit**: Hibernate Envers (entity revision history) + `AuditorAware` (`@CreatedBy`/`@LastModifiedBy`); for tamper-evident — optional lightweight append-only `audit_log` with a hash chain (`hash = H(prev_hash || payload)`), sync-in-tx (strong) vs async (best-effort). Default = Envers + audit-event table; hash-chain optional.
- **Secrets**: default env / `spring.config.import=optional:configtree:/etc/secrets/`; Spring Cloud Vault optional.

### acme-persistence-spring-boot-starter
- JPA/Hibernate base, HikariCP tuning, `@EnableJpaAuditing` (clock-backed `DateTimeProvider`), Flyway (vendor dirs), optimistic locking (`@Version`), Testcontainers base (via `acme-test-support`).
- **Oracle-first portability** (without hard coupling):
  - ID generation **`GenerationType.SEQUENCE`** (portable; NOT `IDENTITY` — Oracle pre-12c doesn't support it, PG differs). Hibernate `enhanced-sequence` pooled optimizer.
  - identifiers ≤30 chars (Oracle pre-12.2 limit; 12.2+/19c = 128, but keep short for compatibility).
  - large text → `CLOB`; no native `BOOLEAN` before Oracle 23c → Hibernate maps to `NUMBER(1)` (transparent).
  - Hibernate dialect auto-detected; avoid reserved words in names.
  - driver `ojdbc11`; for prod optionally UCP instead of Hikari (but Hikari is the default, portable).

### acme-cache / acme-resilience / acme-featureflags
- **cache**: Caffeine (L1) + Redis/Lettuce (L2) two-tier, TTL jitter, `@Cacheable` abstraction.
- **resilience**: Resilience4j presets (Retry/CircuitBreaker/TimeLimiter/Bulkhead) + Micrometer metrics.
- **featureflags**: OpenFeature SDK + flagd provider; `@ConditionalOnMissingBean` in-memory provider as default.

---

## 6. Example choreography

```
Client → Gateway POST /v1/orders (REST, Idempotency-Key)
Gateway → publish CreateOrder (Avro) + projection row (pending)
Orders  ← consume (inbox dedup) → INSERT + Modulith outbox → OrderCreated
Gateway ← OrderCreated → projection (pending → created)
Payments ← OrderCreated → process → PaymentProcessed (outbox)
Gateway ← PaymentProcessed → projection (paid)
Notifications ← PaymentProcessed → notify customer
```
Read-model (projection) lives in the gateway; REST reads from the projection; no sync RPC; trace/principal headers travel with events.

---

## 7. Design invariants

- reusable layer = zero business logic, zero dependency on a specific DB
- effectively-once: Modulith outbox (at-least-once) + inbox dedup (idempotent consume) — NOT Kafka EOS with a DB
- Strong consistency by default (`StronglyConsistent` marker); relaxation is explicit
- starters are auto-disableable (`@ConditionalOnProperty`/`@ConditionalOnMissingBean`); example layer is deletable without a trace
- all configuration via `@Validated @ConfigurationProperties` (fail-fast), no hardcoding
- **Oracle = primary/reference, Postgres swappable** — no vendor-only SQL in the reusable layer; all DDL via Flyway vendor dirs (Oracle reference); ShedLock `usingDbTime()`, `GenerationType.SEQUENCE`, portable types; switching DB = profile + driver + Hibernate dialect, no code changes
- liveness does NOT depend on external deps; readiness does
- per-key ordering: where critical → blocking retry, not `@RetryableTopic`

---

## 8. Test strategy

- Unit: JUnit5 + AssertJ + Mockito (handlers, middleware, decorators)
- Integration: Testcontainers `@TestConfiguration` + `@ServiceConnection` (**Oracle = `gvenzl/oracle-free` / `oracle-xe` — lightweight, fast start vs official XE ~2GB**; `OracleContainer` supports `@ServiceConnection`; Redis, Redpanda — auto; Keycloak — `DynamicPropertyRegistrar`); optional Postgres test profile; shared config in `acme-test-support` `testFixtures`. ⚠️ Oracle container is heavier than PG in CI — reuse base config, keep on a Linux runner.
- Async choreography: Awaitility
- e2e: full `compose.yaml` stack
- ⚠️ avoid the JUnit `@Testcontainers`/`@Container` extension in Spring tests (breaks lifecycle); container reuse local-only

---

## 9. CI/CD + ops

- **CI**: GitHub Actions `actions/setup-java@v5` (temurin 21) + `gradle/actions/setup-gradle@v6` (auto-cache — do NOT also set `cache:gradle`); Testcontainers on `ubuntu-latest`.
- **Format/lint**: Spotless (`palantirJavaFormat`, `ratchetFrom origin/main`) owns formatting; Checkstyle = semantics only (strip whitespace/import rules).
- **Schema compat gate**: `com.github.imflog.kafka-schema-registry-gradle-plugin:2.5.0` `testSchemasTask` on PR; `registerSchemasTask` on main. Avro codegen: `com.github.davidmc24.gradle.plugin.avro`.
- **Deps**: Renovate (understands `libs.versions.toml`; Dependabot+catalog has bugs).
- **Container**: `bootBuildImage` (Paketo, non-root, CDS `BP_JVM_CDS_ENABLED=true`); `-Djarmode=tools` (not `layertools`). Jib if CI has no Docker daemon.
- **Local stack**: `spring-boot-docker-compose` (`developmentOnly`); **Oracle = `gvenzl/oracle-free` (primary)**, Redis/otel-lgtm auto-wire; Redpanda/Keycloak/Oracle-edge — via properties + readiness-check labels (Oracle starts slower — raise healthcheck timeout). Optional `compose.postgres.yaml` overlay for swap.
- **ADR**: MADR 4.0.0 in `docs/decisions/`, `NNNN-kebab.md` + `status:` front-matter; seed `0000-use-madr.md`.

---

## 10. Cross-cutting gotchas (critical)

- Spring Modulith is **not in Boot BOM** — pin `1.4.x` yourself
- Bucket4j **0.13.0** (0.14.x = Boot 4); springdoc **≥2.8.13** (Boot 3.5.0 `NoSuchMethodError`)
- native API versioning = **Boot 4 only**; hand-roll on 3.5
- OTLP props on 3.5 = `management.otlp.tracing.*` (Boot 4 renames them)
- AOP self-invocation → CQRS pipeline explicit, not `@Around`
- `@RetryableTopic` breaks ordering + incompatible with EOS
- Modulith `republish-on-restart` single-instance only
- **Oracle = primary**: Modulith auto-init → off (targeting 19c), Flyway owns DDL; `SERIALIZED_EVENT`→CLOB; ID `SEQUENCE` not `IDENTITY`; identifiers ≤30; Testcontainers `gvenzl/oracle-free`; Oracle starts slower (healthcheck/CI)
- PipelinR groupId = `net.sizovs` (the old one was removed from Central)
- liveness probe without external deps (restart-loop)
- ShedLock `usingDbTime()` mandatory (clock-skew safety)

---

## 10a. Out of scope (non-goals)

Deliberately NOT ported from go-boilerplate:
- **Application-level sharding pool** (Go's `ShardedPool` primitive) and **outbox-relay sharding** — single HikariCP pool per service, no app-level shard routing. Scale via DB-side options if ever needed.
- **Double-entry ledger / money** primitive (domain, not platform).
- **GraalVM native image** (premature; CDS covers startup).
- **Multi-tenancy** (all of it — tenant ctx/claim resolution, MDC, Kafka tenant headers, schema-per-tenant). Belongs above the service (e.g. geo-sharding / routing layer), not in the boilerplate.

---

## 11. Reference repos

- `agelenler/food-ordering-system` — hexagonal + outbox + CQRS + Kafka, multi-module
- `SaiUpadhyayula/spring-boot-3-microservices-course` — Kafka + Keycloak + Grafana/Prometheus/Loki/Tempo + Testcontainers
- `eventuate-tram-examples-customers-and-orders` — choreography sagas + outbox
- Spring Modulith docs — event publication registry + kafka externalization
- PipelinR (`sizovs/PipelinR`), jMolecules (`xmolecules/jmolecules`), ShedLock (`lukas-krecan/ShedLock`)

---

## 12. Still to clarify

- [x] **audit** — Envers + `AuditorAware`; tamper-evident hash-chain ships as an optional disabled module. Hash-chain sync-vs-async = per-service choice when enabled.
- [x] **Boot target** — **3.5 stable**. Hand-roll API versioning (`/v1`), Bucket4j 0.13.0, OTLP props `management.otlp.tracing.*`. No Boot-4 abstraction layer.
- [ ] **target Oracle version** — default 19c (LTS, auto-init off + Flyway). If 23ai — could enable Modulith auto-init + native BOOLEAN. Confirm infra. (currently assumed: 19c-conservative)
- [ ] exact plugin patch versions (Spotless, palantir, avro) — pin at adoption
- [ ] Oracle JDBC driver historically not on Maven Central — verify `ojdbc11` availability (now on Central, but closed networks need the Oracle maven repo)
