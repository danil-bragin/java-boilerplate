---
status: accepted
date: 2026-06-17
---

# Observability: Micrometer OTel bridge + Actuator probes + ShedLock

## Context and Problem Statement

Services need metrics, tracing, health probes, and safe scheduled jobs across replicas, with a
DB-portable locking story (Oracle-first, Postgres swappable).

## Decision Outcome

- Tracing via Micrometer Tracing OTel bridge to OTLP (CNCF-standard wire format), metrics via
  Micrometer with OTLP + Prometheus registries, all behind Spring Boot Actuator.
- Liveness probe is minimal (no external deps, avoiding restart loops); readiness includes the DB.
- Graceful shutdown enabled (`server.shutdown=graceful`).
- Scheduled-job dedup via ShedLock JDBC provider with `usingDbTime()` — clock-skew safe and
  portable across Postgres and Oracle; no extra infrastructure.
- ShedLock pinned to 6.9.2: the 7.x line depends on Spring Framework 7, which is incompatible
  with Spring Boot 3.5 (Spring Framework 6.2). Revisit when upgrading to Spring Boot 4.
- The ShedLock auto-config is ordered `@AutoConfigureAfter(DataSourceAutoConfiguration)` so its
  `@ConditionalOnBean(DataSource)` sees the datasource.
- Config is validated (`@Validated @ConfigurationProperties`) for fail-fast startup.

Full detail: `docs/superpowers/specs/2026-06-17-acme-boilerplate-design.md` §5 (acme-observability).
