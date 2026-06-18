# ADR 0015: antifraud Avro consumer + RiskEngine

**Date:** 2026-06-18
**Status:** Accepted

## Context

The `antifraud` service is the second hop in the transfer saga. It must consume the `TransferRequested` Avro event, apply fraud-detection rules, persist the decision, and emit a `TransferScreened` Avro event — all idempotently.

## Decision

### RiskEngine behind a domain port

`RiskEngine` is a pure domain class (`assess(Money, int) -> RiskDecision`) with no framework or Avro dependencies. A real ML-based engine would implement the same conceptual contract. The rule set (`RiskRules`) is a value object: max amount (10,000 USD standard / 1,000 USD strict) and max velocity (5 prior approved transfers per source account).

### Feature-flagged rule sets

The `antifraud-strict` boolean flag (OpenFeature `InMemoryProvider`, default `false`) selects between standard and strict `RiskRules` at startup. The flag is registered via a `FeatureProvider` bean that overrides the starter's `NoOpProvider`.

### Avro consumer

`TransferRequestedListener` is a `@KafkaListener(topics="transfer-requested", groupId="antifraud")` receiving a `com.acme.bank.contracts.avro.TransferRequested` SpecificRecord. Consumer config: `KafkaAvroDeserializer` + `specific.avro.reader=true` + Schema Registry URL. The listener is `@Transactional` and deduplicates via `Inbox.firstTime("antifraud", transferId)` (backed by the `processed_messages` table).

### Avro outbox emit

The domain `TransferScreened` event is published via `ApplicationEventPublisher` and externalized by Spring Modulith to topic `transfer-screened` as an Avro `com.acme.bank.contracts.avro.TransferScreened` record. Configuration mirrors BANK-2: `enable-json: false`, `KafkaAvroSerializer` producer, `MoneyJacksonConfig` for the outbox JSON store, and the SR url wired on both `spring.kafka.consumer.properties.schema.registry.url` and `spring.kafka.producer.properties.schema.registry.url`.

### `transfer-requested` → `transfer-screened` hop

- Produces (BANK-2 transfers service): `transfer-requested`
- Consumes (antifraud): `transfer-requested`
- Emits (antifraud): `transfer-screened`

## Consequences

- Domain is framework/Avro-free (ArchUnit enforced).
- Idempotent: duplicate `TransferRequested` messages are silently skipped via `Inbox`.
- The IT proves the full Avro consume→screen→emit cycle on Redpanda + Schema Registry for both approve and reject paths.
- `ScreeningStore` out-port lives in the `application` package (not `adapter`) to keep the application layer free of adapter dependencies (ArchUnit verified).
