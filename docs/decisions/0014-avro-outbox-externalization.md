---
status: accepted
date: 2026-06-18
---

# Avro outbox externalization (transfers)

## Decision Outcome

- A service emits a clean domain event (`TransferRequested`, no Modulith/Avro in the domain) from a
  `StronglyConsistent` command. Spring Modulith persists it to the outbox in the same transaction and,
  after commit, externalizes it. An `EventExternalizationConfiguration` `.mapping(DomainEvent.class,
  domain -> avroIntegrationEvent)` converts it to the `bank-contracts` Avro record and routes it to the
  topic (keyed by transfer id); the Kafka producer uses `KafkaAvroSerializer` against the Schema Registry.
- The domain → Avro mapping lives in an adapter (`TransferAvroMapper` / `TransferExternalizationConfig`),
  keeping the domain framework- and wire-format-free (ArchUnit-enforced).
- Modulith's default JSON-over-Kafka mode (`spring.modulith.events.kafka.enable-json`) must be disabled
  (`false`) when using `KafkaAvroSerializer`; otherwise `KafkaJacksonConfiguration` installs a
  `ByteArrayJsonMessageConverter` that intercepts the Avro payload and fails with a JSON conversion error.
- Verified end-to-end (`TransferExternalizationIT`): initiating a transfer produces a `TransferRequested`
  Avro record on the `transfers` topic, deserialized via `KafkaAvroDeserializer` against the Redpanda
  Schema Registry — schema auto-registered, money as `{amount,asset}` strings (no float).

Full design: `docs/superpowers/specs/2026-06-18-acme-bank-design.md` §7/§8/§9.
