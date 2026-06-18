# BANK-9: deployable stack — Keycloak, observability, scaling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** Make the whole acme-bank system runnable with one command: all five services containerized, fronted by a Keycloak-secured gateway, talking to Postgres (one DB per service) + Redpanda/Schema-Registry, exporting traces/metrics/logs to an OpenTelemetry stack (otel-lgtm/Grafana), horizontally scalable (multiple replicas, with a Redis-backed shared idempotency store so the scaled gateway stays correct), and with distributed traces propagating through Kafka.

**Architecture:** A `compose.bank.yaml` brings up infra (Postgres, Redpanda+SR, Keycloak with a seeded realm, otel-lgtm, Redis) + the five services built from per-service Dockerfiles (Spring `bootJar` on an `eclipse-temurin:21-jre` base — deterministic, no buildpack network dependency; `bootBuildImage` documented as an alternative). Services are env-driven (12-factor): datasource/Kafka/issuer/OTLP endpoints come from environment with sensible localhost defaults so `gradle bootRun` still works. The gateway gets a `RedisIdempotencyStore` (acme-web) so idempotency holds across replicas.

**Tech Stack:** Docker Compose, Keycloak 26, otel-lgtm (grafana/otel-lgtm), Redis 7, Postgres 16, Redpanda v24.2.7, Spring Boot Actuator, Micrometer Tracing (OTLP), Spring Kafka observation.

> Spec: `docs/superpowers/specs/2026-06-18-acme-bank-production-grade-design.md` §4. Builds on BANK-0..8.
> **Environment (every task):** work from `/Users/npden4ik/Projects/java-boilerplate`; system `gradle` (8.14) NEVER `./gradlew`; JDK 21; Docker up. `gradle <module>:spotlessApply` before commits. NOTE: pulling Keycloak/otel-lgtm images may be slow on first run — if an image pull blocks a test, report it rather than hanging; the config tasks (compose validation, JSON validity, the Redis store IT) must still pass.

---

## Task 1: acme-web — Redis-backed shared idempotency store (TDD)

**Files:** `acme-web-spring-boot-autoconfigure/.../web/RedisIdempotencyStore.java`, autoconfigure wiring (`IdempotencyAutoConfiguration`), `build.gradle.kts` (spring-data-redis as optional/compileOnly + test), `RedisIdempotencyStoreIT.java`.

- [ ] **Step 1:** Add Spring Data Redis to acme-web-autoconfigure as an OPTIONAL dependency (`compileOnly` + `testImplementation`) so acme-web doesn't force Redis on every app:
```kotlin
    compileOnly("org.springframework.boot:spring-boot-starter-data-redis")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis")
    testImplementation(libs.testcontainers.junit) // + a Redis container (GenericContainer redis:7-alpine)
```
- [ ] **Step 2: failing IT** `RedisIdempotencyStoreIT` (Redis Testcontainer `redis:7-alpine`, `StringRedisTemplate`): `reserve(k)` true first then false (SETNX semantics); `find` empty while only reserved; after `complete(k,resp)` `find` returns it; `release(k)` only removes an in-progress (not a completed) key; reservations + completions carry a TTL (assert `getExpire` > 0). Two threads reserving the same key → exactly one true.
- [ ] **Step 3: run, FAIL.**
- [ ] **Step 4:** Implement `RedisIdempotencyStore implements IdempotencyStore` (the BANK-6 interface: `find/reserve/complete/release`):
  - key namespace `idem:{key}`; value = a small JSON/string encoding state (IN_PROGRESS) or the serialized `StoredResponse` (status|contentType|base64 body) for COMPLETED.
  - `reserve` = `redis.opsForValue().setIfAbsent("idem:"+k, IN_PROGRESS, ttl)` (atomic SETNX+EX) → returns the Boolean.
  - `complete` = `set` the serialized response with ttl (overwrites the reservation).
  - `find` = get; return present only if it decodes to a COMPLETED response.
  - `release` = delete only if current value is IN_PROGRESS (a Lua CAS or `WATCH`/MULTI; a simple GET-then-DEL guarded is acceptable for the example — comment the small race window).
  - TTL configurable (default 24h), constructor `(StringRedisTemplate, Duration)`.
- [ ] **Step 5: wire** `IdempotencyAutoConfiguration`: `@Bean @ConditionalOnMissingBean(IdempotencyStore.class) @ConditionalOnClass(StringRedisTemplate.class) @ConditionalOnBean(StringRedisTemplate.class)` → `RedisIdempotencyStore`; the in-memory store remains the fallback `@ConditionalOnMissingBean` when no Redis. Ensure ordering (`@AutoConfiguration(after = RedisAutoConfiguration.class)` — recurring repo invariant: `@ConditionalOnBean` needs the contributor configured first).
- [ ] **Step 6: run, PASS** — `gradle :starters:acme-web-spring-boot-autoconfigure:test --tests "*RedisIdempotencyStoreIT"`.
- [ ] **Step 7: commit**
```bash
gradle :starters:acme-web-spring-boot-autoconfigure:spotlessApply
git add starters/acme-web-spring-boot-autoconfigure
git commit -m "feat(acme-web): Redis-backed shared idempotency store for multi-instance deployments"
```

---

## Task 2: services — 12-factor container config + actuator health + OTLP

**Files:** each service `src/main/resources/application.yaml` (env-driven overrides + a `compose` profile section or env placeholders), each `build.gradle.kts` (ensure actuator + micrometer-tracing-otlp present via observability starter), gateway adds redis starter.

- [ ] **Step 1:** Confirm `:starters:acme-observability-spring-boot-starter` brings actuator + micrometer-tracing-bridge-otel + the OTLP exporter. If the OTLP metric/trace exporter isn't already wired, add `io.micrometer:micrometer-tracing-bridge-otel` + `io.opentelemetry:opentelemetry-exporter-otlp` to that starter (catalog). Each service depends on it already.
- [ ] **Step 2:** Make every service's `application.yaml` env-overridable with localhost defaults (Spring already binds `${ENV:default}`). Ensure these resolve from env:
```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/<service>}
    username: ${SPRING_DATASOURCE_USERNAME:acme}
    password: ${SPRING_DATASOURCE_PASSWORD:acme}
  kafka:
    bootstrap-servers: ${SPRING_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    properties:
      schema.registry.url: ${SCHEMA_REGISTRY_URL:http://localhost:8081}
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${OAUTH2_ISSUER_URI:http://localhost:8082/realms/bank}
management:
  endpoints.web.exposure.include: health,info,prometheus
  endpoint.health.probes.enabled: true
  health.livenessstate.enabled: true
  health.readinessstate.enabled: true
  tracing.sampling.probability: 1.0
  otlp.tracing.endpoint: ${OTEL_OTLP_TRACES_ENDPOINT:http://localhost:4318/v1/traces}
otel: {}
```
  (Use the property names the observability starter actually expects — read it. Prefer `issuer-uri` over `jwk-set-uri` so Keycloak discovery works; if the starter hardcodes `jwk-set-uri`, switch it to support `issuer-uri`.)
- [ ] **Step 3:** gateway `build.gradle.kts`: add `implementation("org.springframework.boot:spring-boot-starter-data-redis")` + set `SPRING_DATA_REDIS_HOST/PORT` env (so the `RedisIdempotencyStore` activates in compose; local default no-redis → in-memory store).
- [ ] **Step 4:** A test/verification that the `compose` profile/env resolves: a lightweight `@SpringBootTest` per service is overkill — instead assert via a `ConfigDataIT` or simply rely on existing ITs (they boot with defaults). Add nothing heavy; ensure existing ITs still pass with the new placeholder syntax. Run each module's tests.
- [ ] **Step 5: commit**
```bash
git add examples/acme-bank starters/acme-observability-spring-boot-autoconfigure
git commit -m "feat(bank): 12-factor container config (env-driven datasource/kafka/issuer/OTLP) + health probes"
```

---

## Task 3: Keycloak realm seed

**Files:** `examples/acme-bank/keycloak/realm-bank.json`.

- [ ] **Step 1:** Author `realm-bank.json` — realm `bank`, enabled:
  - clients: `bank-gateway` (confidential or `publicClient:true` with `directAccessGrantsEnabled:true` for password grant in e2e; standard flow on), `bank-swagger` (`publicClient:true`, redirect `*` for swagger-ui auth-code) — for the example, make `bank-gateway` public + direct-access so the e2e can fetch a token via password grant.
  - realm roles: `customer`, `teller`.
  - users: `alice` (password `alice`, role `customer`, email), `teller` (password `teller`, role `teller`). `enabled:true`, `emailVerified:true`, credentials with `"temporary": false`.
  - token settings: reasonable access-token lifespan (e.g. 300s).
  - Ensure `"sslRequired": "none"` (dev) so HTTP works in compose.
- [ ] **Step 2:** Validate JSON: `python3 -c "import json,sys; json.load(open('examples/acme-bank/keycloak/realm-bank.json'))" && echo OK` (or `jq . < file >/dev/null`).
- [ ] **Step 3: commit**
```bash
git add examples/acme-bank/keycloak/realm-bank.json
git commit -m "feat(bank): seed Keycloak realm (bank: customer/teller roles, alice/teller users, gateway/swagger clients)"
```

---

## Task 4: per-service Dockerfiles + Postgres multi-db init

**Files:** `examples/acme-bank/{gateway,transfers,accounts,antifraud,notifications}/Dockerfile`, `examples/acme-bank/db/init-multidb.sh`.

- [ ] **Step 1:** A reusable Dockerfile per service (jar-copy on a JRE base — deterministic, no buildpack network):
```dockerfile
# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jre AS run
WORKDIR /app
ARG JAR=build/libs/*.jar
COPY ${JAR} app.jar
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
```
  (Compose `build.context` points at the service dir; the jar must be built first via `gradle :examples:acme-bank:<svc>:bootJar`. Document this ordering in the README — or use a multi-stage Dockerfile that runs gradle, but that re-downloads deps; jar-copy + a `gradle bankJars` aggregate task is faster/deterministic. Provide a root task:)
- [ ] **Step 2:** Add a root aggregate task in the appropriate `build.gradle.kts` (root or examples):
```kotlin
tasks.register("bankJars") {
    dependsOn(
        ":examples:acme-bank:gateway:bootJar",
        ":examples:acme-bank:transfers:bootJar",
        ":examples:acme-bank:accounts:bootJar",
        ":examples:acme-bank:antifraud:bootJar",
        ":examples:acme-bank:notifications:bootJar",
    )
}
```
- [ ] **Step 3:** Postgres multi-db init `db/init-multidb.sh` (creates a database per service so each owns its schema/migrations):
```bash
#!/bin/bash
set -e
for db in gateway transfers accounts antifraud notifications; do
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    CREATE DATABASE $db;
    GRANT ALL PRIVILEGES ON DATABASE $db TO $POSTGRES_USER;
EOSQL
done
```
- [ ] **Step 4: commit**
```bash
git add examples/acme-bank/*/Dockerfile examples/acme-bank/db build.gradle.kts
git commit -m "feat(bank): per-service Dockerfiles (temurin jre + bootJar) + Postgres multi-db init + bankJars task"
```

---

## Task 5: compose.bank.yaml — full stack, scaled, observable

**Files:** `examples/acme-bank/compose.bank.yaml` (extend the existing infra-only file).

- [ ] **Step 1:** Extend `compose.bank.yaml` to include, with healthchecks + `depends_on: {condition: service_healthy}`:
  - **postgres** (postgres:16): mount `db/init-multidb.sh` into `/docker-entrypoint-initdb.d/`; user/pass `acme`; healthcheck `pg_isready`.
  - **redpanda** (redpandadata/redpanda:v24.2.7) + built-in Schema Registry (8081); healthcheck `rpk cluster health`. (Keep the existing definition.)
  - **redis** (redis:7-alpine), healthcheck `redis-cli ping`.
  - **keycloak** (quay.io/keycloak/keycloak:26.0): `command: start-dev --import-realm`, mount `keycloak/realm-bank.json` → `/opt/keycloak/data/import/`, `KC_HOSTNAME` fixed, port 8082:8080, healthcheck on `/realms/bank`.
  - **otel-lgtm** (grafana/otel-lgtm): ports 3000 (Grafana), 4317/4318 (OTLP); the all-in-one traces+metrics+logs backend.
  - **services** gateway(8080:8080, the only published edge), transfers, accounts, antifraud, notifications — each `build: {context: ./<svc>}`, env wired to the infra (SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/<svc>, SPRING_KAFKA_BOOTSTRAP_SERVERS=redpanda:9092, SCHEMA_REGISTRY_URL=http://redpanda:8081, OAUTH2_ISSUER_URI=http://keycloak:8080/realms/bank, OTEL_OTLP_TRACES_ENDPOINT=http://otel-lgtm:4318/v1/traces; gateway also SPRING_DATA_REDIS_HOST=redis), each `depends_on` its infra healthy, each healthcheck `wget -qO- localhost:8080/actuator/health/readiness`.
  - Note the issuer mismatch caveat: services validate `iss=http://keycloak:8080/realms/bank` (internal); a token fetched from the host via `localhost:8082` has a different `iss`. Use a consistent `KC_HOSTNAME_URL`/issuer so internal + external agree (set `KC_HOSTNAME=keycloak`, and have the e2e fetch tokens via the compose network / or set frontendUrl). Document this in the README and pick ONE issuer URL used everywhere.
- [ ] **Step 2:** Validate: `docker compose -f examples/acme-bank/compose.bank.yaml config >/dev/null && echo "compose valid"`.
- [ ] **Step 3: commit**
```bash
git add examples/acme-bank/compose.bank.yaml
git commit -m "feat(bank): full deployable compose stack (5 services + postgres/redpanda/keycloak/redis/otel-lgtm, healthchecks, scalable)"
```

---

## Task 6: distributed tracing through Kafka (observation) + verify

**Files:** outbox/messaging starters or service config to enable Kafka observation; a note/test.

- [ ] **Step 1:** Enable Spring Kafka observation so W3C `traceparent` propagates producer→broker→consumer:
  - producer side (outbox externalization / KafkaTemplate): `spring.kafka.template.observation-enabled: true`.
  - consumer side: `spring.kafka.listener.observation-enabled: true`.
  - Ensure `micrometer-tracing-bridge-otel` is on the classpath (from the observability starter) so the propagator writes `traceparent` into Kafka headers.
  - Add these to the bank services' `application.yaml` (or the messaging/outbox starter defaults if appropriate).
- [ ] **Step 2:** Verify propagation WITHOUT the full stack: a focused IT in one service (e.g. transfers or accounts) that produces an event and asserts the outbound Kafka record carries a `traceparent` header (consume the raw record, assert `record.headers().lastHeader("traceparent") != null`). This proves context is injected; the cross-service join is then visible in Grafana (manual/e2e). If the existing externalization IT can be extended to assert the header, do that.
- [ ] **Step 3:** If `traceparent` is NOT present, fix: ensure the KafkaTemplate used by the outbox is observation-enabled and the ObservationRegistry is wired. Report precisely what was needed.
- [ ] **Step 4: commit**
```bash
gradle :examples:acme-bank:transfers:spotlessApply
git add examples/acme-bank
git commit -m "feat(bank): propagate distributed trace context through Kafka (observation) + header assertion IT"
```

---

## Task 7: README (run/scale/observe) + ADR

**Files:** `examples/acme-bank/README.md` (extend), `docs/decisions/0020-deployable-stack.md`.

- [ ] **Step 1:** Extend the README with a "Run the whole system" section:
  - `gradle bankJars` then `docker compose -f examples/acme-bank/compose.bank.yaml up --build`.
  - URLs: gateway http://localhost:8080 (+ /swagger-ui.html), Keycloak http://localhost:8082, Grafana http://localhost:3000.
  - get a token: `curl -d 'client_id=bank-gateway&username=alice&password=alice&grant_type=password' http://localhost:8082/realms/bank/protocol/openid-connect/token`.
  - drive a transfer through the gateway with the token; watch the trace in Grafana span gateway→transfers→Kafka→accounts.
  - scale: `docker compose -f ... up --scale transfers=2 --scale accounts=2` — explain WHY it's safe (inbox dedup + DB-anchored posting per replica; the gateway shares idempotency via Redis).
  - the issuer-URL caveat.
- [ ] **Step 2:** ADR `0020-deployable-stack.md` — decisions: Dockerfile(jar-copy) over buildpacks (determinism/offline) with bootBuildImage as alternative; one Postgres DB per service; Keycloak seeded realm + the single-issuer-URL rule; otel-lgtm for the OTel backend; horizontal scaling correctness (per-replica inbox/posting anchors + Redis shared idempotency at the edge); Kafka trace propagation via observation. Consequences + alternatives (k8s/Helm deferred).
- [ ] **Step 3: commit**
```bash
git add examples/acme-bank/README.md docs/decisions/0020-deployable-stack.md
git commit -m "docs: run/scale/observe README + ADR 0020 deployable stack"
```

---

## Task 8: full build

- [ ] **Step 1:** `gradle build` → BUILD SUCCESSFUL (all ITs incl. the Redis store IT + the traceparent IT pass).
- [ ] **Step 2:** `docker compose -f examples/acme-bank/compose.bank.yaml config >/dev/null` → valid. (A full `up` is exercised by BANK-10 e2e; if you can `up` quickly to smoke it, great, but don't block on slow image pulls — report instead.)

---

## Done criteria for BANK-9

- `RedisIdempotencyStore` provides cross-replica idempotency (Redis Testcontainer IT green); in-memory remains the no-Redis default.
- Every service is env-driven (datasource/kafka/issuer/OTLP) with health probes; gateway uses Redis when present.
- Keycloak realm seed (valid JSON) with roles/users/clients.
- Per-service Dockerfiles + `bankJars` + Postgres multi-db init.
- `compose.bank.yaml` brings up all five services + infra with healthchecks; `docker compose config` validates; scalable.
- Trace context propagates through Kafka (header-assertion IT green).
- README run/scale/observe + ADR 0020.
- `gradle build` green.

---

## Self-review notes

- **Spec coverage:** §4 images (T4), compose all-services+infra (T5), Keycloak seed (T3), observability/OTLP (T2,T6), scaling + shared idempotency (T1,T2,T5), trace-through-Kafka (T6) ✓.
- **Type consistency:** `RedisIdempotencyStore implements IdempotencyStore` (find/reserve/complete/release from BANK-6); env var names consistent between `application.yaml` (`${SPRING_DATASOURCE_URL:...}`) and `compose.bank.yaml`.
- **No placeholders:** Dockerfile, init script, compose services concrete. Keycloak client secret-less (public + direct-access) chosen deliberately for a runnable example — noted.
- **Recurring invariant:** `RedisIdempotencyStore` `@ConditionalOnBean(StringRedisTemplate)` requires `@AutoConfiguration(after=RedisAutoConfiguration)` — called out in T1S5.
- **Risk:** image pulls (Keycloak/otel-lgtm) may be slow first-run — tasks that block on pulls must report, not hang; the gradle-side verifications (Redis IT, traceparent IT, compose config validation, realm JSON validity) are the hard gates. The single-issuer-URL rule prevents the classic Keycloak `iss` mismatch 401.
