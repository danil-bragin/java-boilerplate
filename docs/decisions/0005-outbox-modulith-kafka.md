---
status: accepted
date: 2026-06-17
---

# Transactional outbox via Spring Modulith Event Publication Registry + Kafka externalization

## Context and Problem Statement

Event choreography needs reliable publish: a domain event must be persisted atomically with the
state change and delivered to Kafka, without dual-write loss and without XA.

## Decision Outcome

- Use Spring Modulith's JPA-backed Event Publication Registry as the transactional outbox: an
  `@Externalized` domain event published via `ApplicationEventPublisher` inside the business
  transaction is written to `event_publication` in that same transaction (at-least-once), then
  published to Kafka after commit and marked complete.
- The `acme-outbox` starter bundles `spring-modulith-starter-jpa` + `-events-jackson` (JSON) +
  `-events-kafka`. Modulith self-configures; the app declares the event + topic via `@Externalized`.
- `JpaEventPublication` is a JPA entity, so with `ddl-auto: validate` the `event_publication` table
  must exist before startup: it is created by Flyway (V3 migration), consistent with the Flyway-owns-DDL
  approach. (On the JPA events path Modulith ships no schema initializer, so Flyway solely owns the table.)
- Effectively-once end-to-end requires pairing this at-least-once outbox with an idempotent inbox
  on the consumer side — deferred to SP-4b (which also switches the wire format to Avro + Confluent
  Schema Registry).
- Spring Modulith pinned to 1.4.6 (the 1.4.x line targets Spring Boot 3.5; 2.x targets Boot 4).

Full detail: `docs/superpowers/specs/2026-06-17-acme-boilerplate-design.md` §5 (acme-outbox).
