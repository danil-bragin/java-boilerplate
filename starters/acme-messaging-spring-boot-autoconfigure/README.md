# acme-messaging-spring-boot-autoconfigure

Auto-configures the consumer side of the saga messaging stack: a JDBC idempotency inbox, a Kafka
error handler that retries then routes poison records to a dead-letter topic, and a listener
container factory that passes raw deserialized payloads through.

## What it configures

`MessagingAutoConfiguration` (`@AutoConfiguration(after = {JdbcTemplateAutoConfiguration, KafkaAutoConfiguration})`,
gated `@ConditionalOnClass(JdbcTemplate)`):

- **`Inbox` bean** (`JdbcInbox`, gated `@ConditionalOnBean(JdbcTemplate)` + `@ConditionalOnMissingBean`) —
  `firstTime(listener, messageId)` records a `(listener, message_id)` pair in a `processed_messages`
  table and returns `true` only the first time. Uses a conflict-ignoring upsert (Postgres
  `INSERT ... ON CONFLICT DO NOTHING`, Oracle `MERGE`) so a duplicate is a clean no-op that does not
  poison a batch transaction. Call it in the same transaction as the side effect.
- **`kafkaErrorHandler`** (`DefaultErrorHandler`, gated `@ConditionalOnBean(ProducerFactory)` +
  `@ConditionalOnMissingBean`) — retries with `FixedBackOff(200ms × 2)` then publishes to `<topic>-dlt`
  (same partition) via a dedicated DLT `KafkaTemplate` derived from the auto-configured `ProducerFactory`.
  Key serializer is fixed to `StringSerializer`; the value serializer is a `DelegatingByTypeSerializer`
  (`byte[]`→`ByteArraySerializer`, `String`→`StringSerializer`, and — only when Confluent's
  `KafkaAvroSerializer` and Avro are on the classpath AND a `schema.registry.url` is configured — Avro
  `SpecificRecord`/`GenericRecord`/`IndexedRecord`→`KafkaAvroSerializer`, resolved reflectively). On each
  routed record it increments a Micrometer counter and logs a WARN.
- **`stringKafkaListenerContainerFactory`** (`ConcurrentKafkaListenerContainerFactory`, gated
  `@ConditionalOnBean(ConsumerFactory)` + `@ConditionalOnMissingBean(name=...)`) — configured through
  Boot's `ConcurrentKafkaListenerContainerFactoryConfigurer` (so the error handler is attached), then
  its record converter is overridden to a plain `MessagingMessageConverter` so raw `String` payloads
  pass through unmodified (bypassing Modulith's global `ByteArrayJsonMessageConverter`).

## Metrics

| Meter | Tags | Meaning |
|---|---|---|
| `acme.saga.dlt` (counter) | `topic` = source topic | Incremented once per record routed to a DLT. No-op if no `MeterRegistry` bean (Micrometer is `compileOnly`). |

## Usage

```kotlin
implementation("acme-bank:acme-messaging-spring-boot-starter")
```

With a `DataSource`/`JdbcTemplate` and Spring Kafka on the classpath the inbox, DLT error handler and
string listener factory auto-activate.

## See also

- ADR-0008 — Idempotent consumer (inbox) → effectively-once
- ADR-0015 — antifraud Avro consumer + RiskEngine
- Root README
