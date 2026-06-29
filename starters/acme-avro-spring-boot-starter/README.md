# acme-avro

Standalone aggregator starter (no autoconfigure sibling, no Java source). Pulls the Avro + Confluent
Schema Registry serialization stack so a service can produce/consume typed Avro records over Kafka.

## What it configures

Puts these libraries on the classpath (no beans of its own — Spring Kafka's own auto-configuration
wires producers/consumers from `spring.kafka.*` properties):

- **`spring-kafka`** — Spring's Kafka integration (`KafkaTemplate`, `@KafkaListener`, container factories).
- **`org.apache.avro:avro` 1.11.4** — the Avro runtime; `SpecificRecord`/`GenericRecord` types and
  schema support.
- **`io.confluent:kafka-avro-serializer` 7.9.1** — Confluent's `KafkaAvroSerializer` /
  `KafkaAvroDeserializer`, which register and resolve schemas against a Confluent Schema Registry
  (configured via `schema.registry.url`).

## Usage

```kotlin
implementation("acme-bank:acme-avro-spring-boot-starter")
```

On the classpath, configure Spring Kafka to use the Confluent Avro (de)serializers and point
`schema.registry.url` at your registry; Avro `SpecificRecord` payloads then flow over Kafka.

## See also

- ADR-0009 — Avro + Confluent Schema Registry as the typed wire-format option
- Root README
