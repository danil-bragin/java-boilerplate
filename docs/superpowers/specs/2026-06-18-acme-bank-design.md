# acme-bank — Enterprise Banking Example (Design)

> A template-quality, multi-service banking example built on the `acme-*` starters. Subset of the
> "5M MAU bank" reference (`example.png`): money movement across choreographed microservices, each
> internally **hexagonal + DDD + Spring Modulith**, exercising every starter with real Testcontainers
> tests. Realistic core (double-entry ledger, ACID transfer, antifraud, effectively-once), mocked
> external edges (rails, notification delivery, ML).
> Status: brainstorm complete; architecture + money model fixed (deep research on 2025–26 Java
> architecture; money mirrors the Go `platform/money`). Date: 2026-06-18.

---

## 1. Goal & positioning

A second deletable example alongside `examples/demo-service`. Where `demo-service` proves each starter
in isolation, **`acme-bank` proves them composed into a realistic, choreographed banking domain** —
and the services themselves are reference templates for how to structure a production Spring Boot 3.5
service (hexagonal, DDD, ArchUnit-enforced).

Non-goal: reproduce the full 40–60-engineer system. We build a representative money-movement vertical
with enough breadth (5 bounded contexts) to be credible, deep enough in the ledger to be real.

## 2. Fixed decisions

| # | Decision | Choice | Rationale (research-backed) |
|---|---|---|---|
| Topology | services | **Microservices outside** (5 services + shared contracts), choreographed via Kafka | exercises outbox/messaging/inbox across the wire; matches `example.png` |
| Per-service | internal style | **Hexagonal + DDD + Spring Modulith**, ArchUnit-enforced, jMolecules stereotypes | gold-standard for serious services (`buckpal`); Modulith is the 2025–26 movement; templates must teach good judgment |
| Core depth | ledger | **accounts = deep hexagonal protected core** (double-entry); periphery lighter | "protected core where it pays off" (Drotbohm/Three Dots); ledger justifies the ceremony |
| Money | representation | **Mirror Go `platform/money`** — `BigDecimal` + asset registry, `NUMERIC+asset`, string-on-wire, exact arithmetic, banker's rounding | one Money for fiat+crypto+FX, no float, no int64 overflow (ADR-0020) |
| Saga | coordination | **Choreography with a coordinating `Transfer` aggregate** (orchestration-by-events) | auditability of banking + our event infra; reserve-then-post, no compensation needed |
| Contracts | wire format | **Avro + Confluent Schema Registry**; domain events (clean) mapped to Avro integration events at the outbox boundary | typed cross-service contracts + enforced compatibility (fork B1); domain stays clean |
| Realism | split | core realistic, external out-adapters mocked behind real ports | testable + shows where real integrations plug in |
| DB | per service | **db-per-service** (own schema, Flyway per service), Postgres (Oracle reference) | matches `example.png`; DB-agnostic invariant holds |

## 3. The `acme-money` module (mirror of Go `platform/money`)

A new reusable value-type library `starters/acme-money` (not auto-config — a pure domain library;
shipped as a plain module consumed by services + a thin `acme-money-spring-boot-starter` only if JPA
mapping helpers are auto-registered). Mirrors ADR-0020 of the Go boilerplate.

- **`Money`** — immutable value object: `record Money(BigDecimal amount, Asset asset)` with the
  `BigDecimal` fully encapsulated (no `BigDecimal` in public arithmetic signatures where avoidable).
  `BigDecimal` is the Java arbitrary-precision decimal (the shopspring/decimal equivalent): exact
  `+ − ×` across mixed scales, no overflow ceiling.
- **No float path.** Construction only from strings / integers: `Money.of(String, Asset)`,
  `Money.ofMinor(BigInteger minor, Asset)`, `Money.ofMajor(long major, Asset)`. **No `fromDouble`.**
- **Exact arithmetic, explicit rounding.** `add`/`subtract`/`multiply` never round (scale grows).
  Division is explicit: `divide(BigDecimal divisor, int scale, RoundingMode)` with guard digits;
  default rounding **`HALF_EVEN`** (banker's). Splitting money among recipients uses
  `allocate(int... ratios)` / `split(int n)` (Fowler allocation at the smallest unit, sign-aware) —
  conserves every minor unit. Round **once** at the boundary.
- **Same-asset invariant.** Binary ops on differing assets throw `CurrencyMismatchException`
  (an `ApiException` with a stable code); no implicit coercion.
- **`Asset` registry in code** (not a table): `Asset` (code + exponent), seeded with ISO-4217 fiat
  (USD/EUR=2, JPY=0, BHD/KWD=3) + crypto (ETH=18, USDC=6). Available without a query, identical
  across services.
- **Storage: `amount NUMERIC + asset VARCHAR`.** A JPA `@Embeddable Money` (or `AttributeConverter`)
  maps to two columns; Postgres `NUMERIC` / Oracle `NUMBER` — arbitrary precision, lossless. NOT the
  PG `money` type, NOT float. Nullable variant for nullable columns.
- **Wire: string.** Avro represents `Money` as `{ amount: string, asset: string }` (a JSON/Avro
  number would be float-parsed; Avro `decimal` logical type fixes scale, too rigid for mixed assets).
- **DoS guard.** Text-parse paths reject > 1000 significant digits or |exponent| > 256.
- **Tests** mirror the Go suite: arithmetic, allocate/split conservation, rounding modes, compare,
  fuzz/property tests, JPA round-trip (Testcontainers), Avro string serde, validation/DoS bounds.

`Money` carries jMolecules `@ValueObject`. Double-entry `Σ = 0` is exact because decimal addition is
exact within an asset.

## 4. Services (5 + shared contracts)

```
examples/acme-bank/
├── bank-contracts/            Avro .avsc integration-event schemas + codegen (shared typed contracts)
├── gateway/                   REST edge: OAuth2, problem+json, springdoc, idempotency, rate-limit, projection
├── transfers/                 coordinating Transfer aggregate (CQRS saga state machine, outbox)
├── accounts/                  ★ deep hexagonal core: Account + double-entry ledger, ACID posting, Σ=0
├── antifraud/                 consumer + inbox, RiskEngine (rules behind feature flags), screened approve/reject
├── notifications/             consumer + inbox, mocked delivery (log/store) + projection
└── compose.bank.yaml          local stack for the whole example
```

Each service is a Spring Boot app, db-per-service, depending only on the starters it needs.

## 5. Per-service hexagonal structure

```
<svc>/src/main/java/com/acme/bank/<svc>/
├── domain/        aggregates, value objects, domain events, domain services, invariants
│                  — jMolecules stereotypes (@AggregateRoot/@ValueObject/@DomainEvent), NO Spring imports
├── application/   use cases = CQRS command/query handlers; ports: in (driving) + out (driven) interfaces
├── adapter/in/    web/ (REST controllers), messaging/ (Kafka @KafkaListener) — call application ports
├── adapter/out/   persistence/ (JPA repos implementing out-ports), messaging/ (outbox publisher),
│                  external/ (mocked rails/delivery behind out-ports)
└── <Svc>Application.java
<svc>/src/test/java/...
├── domain unit tests (pure), application tests (fake ports),
├── ArchUnit fitness functions (hexagonal layering + jmolecules-archunit DDD rules),
└── integration ITs (Testcontainers: Postgres + Redpanda + Schema Registry)
```

**ArchUnit fitness functions** (per service, run in `gradle build`): domain depends on nothing
framework; dependencies point inward only (adapter → application → domain); aggregates reference other
aggregates by identifier, not object (`JMoleculesDddRules`); naming conventions; no cycles. Spring
Modulith `ApplicationModules.verify()` for in-process module boundaries where a service has multiple
modules.

## 6. Ledger model (accounts core — Formance/Kleppmann)

- **`Account`** (`@AggregateRoot`): `AccountId` (VO), `Iban` (VO), `status`, `@Version` (optimistic
  lock). Balance is **derived** = Σ of the account's ledger entries (or a maintained materialized
  balance column updated in the posting tx, reconciled by a scheduled job — ShedLock).
- **`Posting`** (a balanced transaction): `PostingId`, `transferId` (idempotency key, unique),
  `entries` (≥ 2), immutable/append-only. Domain invariant **`Σ entries.amount == Money.zero(asset)`**.
- **`LedgerEntry`**: `postingId`, `accountId`, signed `Money` (debit negative, credit positive),
  `createdAt`. Append-only; corrections are new postings, never updates (Square "Books" model).
- **Use case `PostTransfer`** (`@CommandHandler`, `StronglyConsistent`): load source + destination,
  check funds (`source.balance ≥ amount`), build a balanced `Posting` (debit source, credit dest),
  persist atomically (one DB tx), emit `LedgerPosted` (or `PostingRejected(INSUFFICIENT_FUNDS)`).
  Idempotent by `transferId` (unique constraint + inbox).
- **Invariants enforced by:** a domain unit test (`Posting` rejects non-zero sum) **and** a DB check
  constraint / reconciliation; ArchUnit ensures the invariant lives in the domain.
- Single currency per posting in v1 (entries balance within one asset). Multi-currency/FX deferred.

## 7. Transfer saga (choreographed, coordinating aggregate)

`Transfer` aggregate state machine, owned by `transfers`:
`REQUESTED → SCREENING → (REJECTED | APPROVED) → POSTING → (COMPLETED | FAILED)`.

```
1. Client → gateway  POST /v1/transfers  (Idempotency-Key header, OAuth2 JWT)
2. gateway → transfers (REST) → Transfer(REQUESTED) persisted + outbox TransferRequested
3. antifraud ⟵ TransferRequested (inbox dedup) → RiskEngine (rules) → outbox TransferScreened(approved|rejected,score,reasons)
4. transfers ⟵ TransferScreened:
     rejected  → Transfer(REJECTED) + outbox TransferFailed(reason=FRAUD)
     approved  → Transfer(APPROVED) + outbox PostingRequested
5. accounts ⟵ PostingRequested (inbox dedup) → PostTransfer (ACID, Σ=0):
     ok               → outbox LedgerPosted
     insufficient     → outbox PostingRejected(INSUFFICIENT_FUNDS)
6. transfers ⟵ LedgerPosted     → Transfer(COMPLETED) + outbox TransferCompleted
            ⟵ PostingRejected  → Transfer(FAILED)    + outbox TransferFailed(reason=INSUFFICIENT_FUNDS)
7. notifications ⟵ TransferCompleted|TransferFailed (inbox) → render + store (mocked delivery)
8. gateway ⟵ TransferRequested|Completed|Failed → update read-model projection;
            GET /v1/transfers/{id} serves status + timeline from the projection (cache-fronted)
```

Effectively-once at every consumer (inbox dedup keyed by event id / `transferId`). Posting is the
last and only money-moving step and is atomic; screening happens before, so **no compensation is
required** (reserve-then-post). Full status timeline is auditable.

## 8. Event contracts (Avro + Schema Registry)

`bank-contracts` holds `.avsc` for every integration event: `TransferRequested`, `TransferScreened`,
`PostingRequested`, `LedgerPosted`, `PostingRejected`, `TransferCompleted`, `TransferFailed`,
`NotificationSent`. `Money` appears as `{amount:string, asset:string}`. Codegen → `SpecificRecord`
types shared by all services.

**Clean-architecture seam:** domain events are clean records in `domain/`; an outbox-publishing
adapter (or Spring Modulith `EventExternalizationConfiguration.mapping(...)`) maps **domain event →
Avro integration event** at the boundary and publishes via `KafkaAvroSerializer` → SR. Consumers
deserialize Avro (`KafkaAvroDeserializer`) → integration event → map to a domain command. Domain never
imports Avro. A CI schema-compatibility gate (imflog plugin) enforces backward compatibility.

## 9. Starter usage & required improvements

**Every starter is exercised:** web/security (gateway), persistence/audit (all), cqrs (transfers,
accounts), outbox (emitters), messaging+inbox (consumers), avro (contracts), cache (projections,
balances), resilience (cross-service calls, rail stub), featureflags (antifraud rules), observability
(all), plus the new `acme-money`.

**Starter work this example drives (fix/polish as we go):**
- `acme-outbox`: **Avro externalization** — map domain event → Avro integration event +
  `KafkaAvroSerializer` producer (currently JSON-only).
- `acme-messaging`: **typed Avro consumer** + inbox (currently String/JSON).
- `acme-cqrs`: finish **jMolecules** integration; add a first-class **Query** side.
- `acme-web`: build the **idempotency filter** + **rate limiting (Bucket4j)** (designed but deferred;
  gateway needs them).
- `acme-persistence`: **`Money` JPA mapping** (`@Embeddable`/`AttributeConverter`), per-service Flyway.
- `acme-test-support`: jMolecules + ArchUnit convenience, Schema Registry test config helper.
- new **`acme-money`** library (§3).

## 10. Testing strategy

- **Domain unit** (no Spring): `Money` arithmetic/allocation/rounding; `Posting` Σ=0; `Transfer` state
  machine transitions; `RiskEngine` rules.
- **ArchUnit fitness** per service: hexagonal layering + jMolecules DDD rules + no cycles.
- **Application** tests: handlers with fake ports.
- **Integration ITs** (Testcontainers Postgres + Redpanda + SR): JPA repos + `Money` round-trip; Avro
  outbox publish; Avro inbox consume; idempotent posting.
- **End-to-end saga IT** (crown jewel): drive a transfer through all services (compose-based or
  multi-context), Awaitility until `Transfer=COMPLETED`, assert the ledger is balanced (Σ=0, source
  debited, dest credited) and a notification was stored. A second e2e asserts the fraud-reject and
  insufficient-funds paths end in `FAILED` with no money moved.
- **Schema-compat** test (Avro contracts).

## 11. Sub-project decomposition (each = spec → plan → build)

- **BANK-0** — skeleton: `examples/acme-bank` multi-module Gradle wiring, `acme-money` library
  (full, with tests), `bank-contracts` (Avro schemas + codegen), ArchUnit/jMolecules conventions,
  `compose.bank.yaml`.
- **BANK-1** — **accounts + ledger**: deep hexagonal core, domain-first (Account/Posting/Entry/Money),
  `PostTransfer` ACID Σ=0, idempotent posting, JPA persistence, audit attribution, ArchUnit, ITs.
- **BANK-2** — **transfers**: `Transfer` saga aggregate + CQRS + outbox; drives the `acme-outbox`
  **Avro externalization** improvement; consumes screening + posting results.
- **BANK-3** — **antifraud**: inbox consumer + `RiskEngine` (deterministic rules) + feature-flagged
  rule sets; drives the `acme-messaging` **Avro consumer** improvement.
- **BANK-4** — **gateway**: REST edge + OAuth2 + projection; drives the `acme-web` **idempotency +
  rate-limit** improvements.
- **BANK-5** — **notifications** + **end-to-end saga IT** (crown jewel) + full `compose.bank.yaml`
  e2e; final review of the whole example.

## 12. Invariants (design)

- Domain layer of every service imports **no** Spring/JPA/Kafka/Avro — enforced by ArchUnit.
- `Money` has no float path; double-entry `Σ entries == 0` per posting per asset, exact.
- Money on the wire is a string; in the DB `NUMERIC + asset`.
- Every Kafka consumer is idempotent (inbox dedup); the saga is effectively-once.
- Posting is the only money-moving step, atomic, idempotent by `transferId`; screening precedes it →
  no compensation.
- db-per-service; no shared tables; reusable starters stay DB-agnostic.
- External integrations (rails, delivery, ML) sit behind real out-ports with mocked implementations.

## 13. Self-review notes

- **Scope:** large but cleanly decomposed into BANK-0..5; each produces working, tested software and
  improves named starters. BANK-0 (money + contracts + skeleton) is the foundation and is built first.
- **Consistency:** `Money` (§3) used by ledger (§6), contracts (§8); saga states (§7) match the events
  (§8); starter improvements (§9) align with the BANK steps (§11).
- **No placeholders.** Mocked parts are explicitly enumerated (§2 realism, §12) behind real ports.
- **Ambiguity resolved:** choreography-with-coordinating-aggregate (not pure choreography nor a central
  orchestrator); single-currency v1; balance derived + materialized with reconciliation.
