# acme-observability

Observability and distributed-scheduling wiring for ACME services. Guarantees correct W3C trace
propagation across services and runs `@Scheduled` jobs on exactly one instance via ShedLock with a
DB-time JDBC lock provider.

## What it configures
`TracePropagationAutoConfiguration` (`@ConditionalOnClass({TextMapPropagator, W3CTraceContextPropagator})`, ordered `before` Boot's `OpenTelemetryTracingAutoConfiguration`):
- `w3cTextMapPropagator()` — W3C `traceparent` `TextMapPropagator` (`@ConditionalOnMissingBean`), so trace context is injected into outbound carriers (HTTP, Kafka headers) instead of Boot falling back to a noop propagator that silently drops `traceparent`.

`SchedulerLockAutoConfiguration` (`@ConditionalOnClass(LockProvider)`, `@ConditionalOnBean(DataSource)`, `@AutoConfigureAfter(DataSourceAutoConfiguration)`, `@EnableSchedulerLock`):
- `lockProvider(DataSource)` — `JdbcTemplateLockProvider` with `usingDbTime()` (`@ConditionalOnMissingBean`), resilient to cross-node clock skew, portable across Postgres and Oracle.
- Default `lockAtMostFor` bound to `acme.observability.scheduler-lock.default-lock-at-most-for` (default `PT10M`).

## Key properties
| Property | Default | Purpose |
|---|---|---|
| `acme.observability.scheduler-lock.default-lock-at-most-for` | `PT10M` | Upper bound a lock is held if the holding node dies mid-job. Validated `@DurationMin(seconds = 1)`. |

## Usage
```kotlin
implementation("acme-bank:acme-observability-spring-boot-starter")
```
Trace-propagation autoconfig activates when the OTel bridge/exporter are on the classpath (the
starter brings them, plus Actuator, Prometheus + OTLP Micrometer registries, and validation); the
ShedLock lock provider activates when a `DataSource` is present.

## See also
- ADR-0003 Observability: Micrometer OTel bridge + Actuator probes + ShedLock
- root README
