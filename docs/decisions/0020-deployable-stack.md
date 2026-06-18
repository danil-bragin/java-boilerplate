# 0020 — Deployable Stack: Keycloak, Observability, Horizontal Scaling

**Date:** 2026-06-18
**Status:** Accepted

## Context

BANK-0..8 built the money-movement saga, the Avro outbox, the antifraud consumer, the account
lifecycle, and a spec-first gateway. Each service ran in isolation under Testcontainers, but there
was no way to bring the **whole system** up: no container images, no single compose file wiring the
five services to shared infrastructure, no identity provider issuing real tokens, no observability
backend, and no story for running more than one replica of a service. BANK-9 makes the system
**deployable and operable**: one command brings up Keycloak (seeded realm), Postgres (one DB per
service), Redpanda + Schema Registry, Redis, an OpenTelemetry backend, and all five services
fronted by the gateway — horizontally scalable, with distributed traces that cross Kafka.

Several questions dominated the design: **how to build images**, **how to issue and validate
tokens consistently**, **how to make scaling correct**, and **how to see a request flow end-to-end**.

## Decision

### Images: jar-copy Dockerfiles on a JRE base (buildpacks as the documented alternative)

Each service is a per-service `Dockerfile` that copies the Spring `bootJar` onto
`eclipse-temurin:21-jre`. `gradle bankJars` builds all five boot jars first; compose
`build.context` points at each service directory. This is **deterministic and offline** — no
buildpack network round-trip, no surprise base-image churn — and fast to rebuild (only the jar
layer changes). The bank-service convention plugin disables the thin `-plain` jar so `build/libs`
holds exactly one artifact, keeping the `COPY build/libs/*.jar` glob unambiguous.

`./gradlew bootBuildImage` (Cloud Native Buildpacks) remains a documented alternative for teams
that want OCI-optimized layered images and don't mind the buildpack download.

### One Postgres database per service

`db/init-multidb.sh` (mounted into `/docker-entrypoint-initdb.d/`) creates
`gateway`/`transfers`/`accounts`/`antifraud`/`notifications` databases on first start. Each service's
`SPRING_DATASOURCE_URL` points at its own database, so each owns its schema and Flyway migrations —
no cross-service table coupling, exactly as if they were separate Postgres instances, but cheaper
for an example.

### Keycloak seeded realm + the single-issuer-URL rule

A committed `keycloak/realm-bank.json` (imported via `start-dev --import-realm`) defines the realm,
the `customer`/`teller` roles, the `alice`/`teller` users, and public `bank-gateway` (direct-access
grant, for password-grant e2e) / `bank-swagger` clients — secret-less by design for a runnable
example.

The load-bearing decision: **every service validates the same issuer**,
`http://keycloak:8080/realms/bank` (the in-network URL), and `KC_HOSTNAME=keycloak` pins the token's
`iss` claim to that URL. Services use `issuer-uri` (OIDC discovery) rather than a hardcoded
`jwk-set-uri`. This avoids the classic Keycloak `iss`-mismatch 401: a token issued under one host
URL but validated against another is rejected. The consequence — a token fetched from the host via
`localhost:8082` is **not** accepted by the services — is documented in the README; e2e must fetch
tokens from within the compose network.

### Horizontal scaling correctness

`transfers` and `accounts` are safe at >1 replica without coordination: each consumed topic has
**per-replica inbox dedup**, and posting is **DB-anchored** (the double-entry `Posting`'s
idempotency anchor has a unique constraint), so concurrent replicas converge to a single posting and
duplicates are no-ops. The **gateway**, being the stateful REST edge, needs shared idempotency:
a `RedisIdempotencyStore` (acme-web) backs reservations/responses with Redis `SETNX`+TTL so a retry
routed to a different replica replays the original response instead of re-executing. With no Redis
(local `bootRun`), the gateway falls back to the in-memory store. This is *why* the Redis store
(BANK-9 Task 1) exists.

### Observability: otel-lgtm + OTLP, and W3C trace propagation through Kafka

Services export traces/metrics/logs over OTLP to the all-in-one `grafana/otel-lgtm` backend
(Grafana + Tempo + Mimir + Loki). Kafka observation is enabled on both ends
(`spring.kafka.template.observation-enabled` / `spring.kafka.listener.observation-enabled`) so the
W3C `traceparent` rides the Avro records, joining a single trace across
**gateway → transfers → Kafka → accounts**.

This required a fix: with `micrometer-tracing-bridge-otel` on the classpath, Boot could still fall
back to `TextMapPropagator.noop()` in some classpath arrangements — silently dropping `traceparent`.
We added `TracePropagationAutoConfiguration` (observability starter) that contributes an explicit
**W3C `TextMapPropagator`** ordered before Boot's `OpenTelemetryTracingAutoConfiguration`, so the
noop propagator never wins. `TransferExternalizationIT` asserts the externalized record carries a
`traceparent` header — a regression guard for the cross-service trace join.

## Consequences

- One command (`gradle bankJars && docker compose -f examples/acme-bank/compose.bank.yaml up --build`)
  brings up the entire system; `docker compose config` validates the stack in CI.
- Tokens must be fetched in-network (single-issuer rule) — a deliberate trade for correct `iss`
  validation. Documented prominently.
- `transfers`/`accounts`/`antifraud`/`notifications` now depend on the observability starter (they
  previously did not), bringing actuator + the OTel bridge so health probes and trace propagation
  work uniformly.
- The Redis dependency is optional everywhere except the gateway: it's `compileOnly` in acme-web and
  only the gateway adds it as a runtime dependency; the test profiles exclude `RedisAutoConfiguration`
  so ITs keep the in-memory store.

## Alternatives Considered

- **Buildpacks (`bootBuildImage`) as the default** — rejected for determinism/offline reasons;
  documented as an alternative.
- **A shared single Postgres database** — rejected; per-service databases keep schema ownership clean
  and model production topology.
- **Confidential Keycloak clients with secrets** — rejected for the example; public + direct-access
  grant keeps the e2e token fetch a one-liner. A real deployment would use confidential clients.
- **Kubernetes / Helm** — deferred. Compose is enough to prove the deployable, scalable, observable
  shape; a Helm chart is a natural follow-up (BANK-N).
