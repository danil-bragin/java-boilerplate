---
status: accepted
date: 2026-06-18
---

# acme-bank skeleton: Avro contracts + service conventions

## Decision Outcome

- `examples/acme-bank` is the enterprise banking example: choreographed microservices, each internally
  hexagonal + DDD + Spring Modulith, exercising every starter.
- `bank-contracts` holds shared Avro integration-event schemas (`Money` as a `{amount,asset}` string
  record so no float crosses the wire); `MoneyMapper` bridges the `acme-money` `Money` value type to/from
  the Avro contract. Schemas grow per service in BANK-1..5.
- `acme.bank-service-conventions` is the one build plugin each bank service applies: Java 21 + Spotless
  (from `acme.java-conventions`) + jMolecules DDD stereotypes (main) + ArchUnit + jmolecules-archunit
  (test) — making hexagonal/DDD boundaries enforceable fitness functions.
- `compose.bank.yaml` provides Postgres, Redpanda (+ Schema Registry), Keycloak (port 8082), and the
  otel-lgtm observability stack.

Full design: `docs/superpowers/specs/2026-06-18-acme-bank-design.md`.
