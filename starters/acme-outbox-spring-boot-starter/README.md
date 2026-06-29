# acme-outbox

Standalone aggregator starter (no autoconfigure sibling, no Java source). Pulls the Spring Modulith
event-publication stack so an application gets a transactional outbox: domain events are persisted in
the same database transaction as the business write, then externalized to Kafka at-least-once.

## What it configures

Puts these Spring Modulith 1.4.6 modules (and their Boot auto-configuration) on the classpath:

- **`spring-modulith-starter-jpa`** — the JPA-backed Event Publication Registry. Each application event
  is recorded in an `event_publication` table inside the publishing transaction, then marked completed
  once its listener succeeds; incomplete entries are republished. Brings JPA persistence for the registry.
- **`spring-modulith-events-jackson`** — Jackson (JSON) serialization of event payloads stored in the
  registry.
- **`spring-modulith-events-kafka`** — externalizes events annotated `@Externalized` to Kafka. Pulls
  `spring-kafka` transitively.

The `event_publication` table is owned by the application's Flyway migrations (Oracle 19c compatible),
not created by this starter.

## Usage

```kotlin
implementation("acme-bank:acme-outbox-spring-boot-starter")
```

On the classpath Spring Modulith auto-configures the JPA event publication registry and Kafka
externalization; annotate events with `@Externalized` to publish them.

## See also

- ADR-0005 — Transactional outbox via Spring Modulith Event Publication Registry + Kafka externalization
- ADR-0014 — Avro outbox externalization (transfers)
- Root README
