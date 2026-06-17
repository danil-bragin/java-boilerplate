---
status: accepted
date: 2026-06-18
---

# Avro + Confluent Schema Registry as the typed wire-format option

## Decision Outcome

- The `acme-avro` starter bundles `io.confluent:kafka-avro-serializer` + Apache Avro, so a service can
  use `KafkaAvroSerializer`/`KafkaAvroDeserializer` against a Schema Registry for typed, schema-checked
  events. Schemas live as `.avsc` and are code-generated (davidmc24 Avro Gradle plugin) into
  `SpecificRecord` classes.
- Verified end-to-end (`AvroSchemaRegistryIT`): an `OrderEventAvro` round-trips through Redpanda's
  built-in Schema Registry — schema auto-registered on produce, read back with `specific.avro.reader`.
- The Confluent serde lives on the Confluent Maven repo (`packages.confluent.io/maven`), added to the
  build's repositories.
- Scope: Avro is offered as the typed wire-format option (design fork B1) for new event streams. The
  existing Modulith outbox path stays JSON (Modulith externalizes a serialized JSON string, not a
  SpecificRecord); migrating that path to Avro would require a custom externalizer and is out of scope.
- A CI schema-compatibility gate (e.g. the imflog kafka-schema-registry Gradle plugin `testSchemasTask`)
  is the recommended next step to enforce backward/forward compatibility before merge.

Full detail: `docs/superpowers/specs/2026-06-17-acme-boilerplate-design.md` §3/§5.
