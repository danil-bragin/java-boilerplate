---
status: accepted
date: 2026-06-17
---

# Idempotent consumer (inbox) -> effectively-once

## Context and Problem Statement

The Modulith outbox (SP-4) is at-least-once; Kafka can redeliver. Consumers must apply side effects
exactly once without distributed transactions.

## Decision Outcome

- The `acme-messaging` starter provides a JDBC `Inbox`: `firstTime(listener, messageId)` inserts a
  `processed_messages` row (PK `(listener, message_id)`) and returns `false` on duplicate, so a
  handler skips already-applied effects. Called inside the handler transaction, the dedup marker and
  the side effect commit atomically — effectively-once on top of at-least-once delivery.
- A default `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` routes poison records to
  `<topic>-dlt` after bounded retries (Spring Kafka's default suffix). The custom
  `stringKafkaListenerContainerFactory` is built via Boot's
  `ConcurrentKafkaListenerContainerFactoryConfigurer` so this error handler is actually attached;
  `DltRoutingIT` proves a poison record reaches `orders-dlt`.
- The demo consumes externalized `OrderCreated` and builds an `order_projection` read-model
  idempotently; an IT proves the full outbox->Kafka->inbox->projection path applies exactly once, and
  a redelivery applies the projection once.
- The consumer uses a dedicated `stringKafkaListenerContainerFactory` (raw String payloads): Spring
  Modulith registers a global `ByteArrayJsonMessageConverter` for Kafka externalization that would
  otherwise mis-convert inbound JSON for a `String` listener.
- `MessagingAutoConfiguration` is ordered `@AutoConfiguration(after = {JdbcTemplateAutoConfiguration,
  KafkaAutoConfiguration})` so its `@ConditionalOnBean` checks see the JdbcTemplate/KafkaTemplate.
- Wire format stays JSON; Avro + Confluent Schema Registry is a documented future enhancement that
  does not change these mechanics.

Full detail: `docs/superpowers/specs/2026-06-17-acme-boilerplate-design.md` §5 (acme-messaging).
