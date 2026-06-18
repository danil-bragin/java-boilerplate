# 0018 — Spec-First Gateway + CQRS Status Projection

**Date:** 2026-06-18
**Status:** Accepted

## Context

BANK-0..6 built the money-movement core: the transfers saga (ADR-0017), the Avro outbox
externalization (ADR-0014), the antifraud Avro consumer (ADR-0015), and the REST edge concerns —
idempotency + rate-limit + problem+json (ADR-0016). Each owning service exposed its own REST surface.

BANK-7 introduces a single **edge service** (`gateway`, a BFF) in front of the bank. It needs:

1. A **stable, published API contract** that clients and swagger-ui can rely on — independent of any
   one service's internal controllers.
2. A way to answer `GET /v1/transfers/{id}` (and list) **without** fanning out to the owning service
   on every read.
3. **Resilience** around the one synchronous downstream call (`POST /v1/transfers` → transfers).

## Decision

### Spec-first OpenAPI (contract → generated server interfaces, `interfaceOnly`)

The hand-written OpenAPI 3.0.3 contract `bank-api.yaml` is the single source of truth. The
`org.openapi.generator` plugin (generator `spring`, `interfaceOnly=true`, `useSpringBoot3=true`)
generates Spring `*Api` server interfaces into `build/generated/openapi`, wired into the main source
set with `compileJava dependsOn openApiGenerate`. Controllers `implements TransfersApi`, so **any
contract operation without an implementation is a compile error** — drift between contract and code is
caught by `javac`, not by review.

swagger-ui renders the hand-written contract verbatim (`springdoc.swagger-ui.url=/openapi/bank-api.yaml`),
while the code-introspected `/v3/api-docs` is kept enabled to feed a drift guard test
(`OpenApiContractTest`) that asserts every contract operation is present in the served docs.

### BFF gateway as the single edge

The gateway is the one place clients talk to. It owns the edge concerns (OAuth2 JWT, `Idempotency-Key`,
rate-limit, problem+json) via the acme-* edge starters and forwards writes to the owning services.

### CQRS status projection over saga events (read model)

`GET /v1/transfers/{id}` / list are served from a local read model (`transfer_view`) built by
consuming the saga's Avro events (`transfer-requested`, `transfer-screened`, `transfer-completed`,
`transfer-failed`). Each projection listener is **inbox-deduped** (one `processed_messages` row per
`(listener, transferId)`) and the upsert is **rank-guarded**: a status only advances when the incoming
status rank ≥ the stored rank, so redelivered or out-of-order events never regress a transfer.

This is a **read-model projection, not a money optimization** — and is explicitly distinct from the
no-materialized-balance rule. The gateway holds no balances and enforces no money invariants; those
remain owned by accounts/transfers. The projection is purely a denormalized status view to keep reads
off the write path.

### Resilience4j around the downstream call

`POST /v1/transfers` forwards through `RestTransfersClient`, a Spring `RestClient` wrapped with
Resilience4j `@CircuitBreaker` + `@Retry` (instance `transfers`). When the circuit is open or retries
are exhausted, the fallback raises `GatewayUnavailableException` (an `ApiException` mapped to **503**
problem+json by the shared handler).

## Consequences

- **Contract is enforced, not aspirational.** Adding an operation to `bank-api.yaml` forces a
  controller method or the build fails; the drift test guards the served docs.
- **Reads are cheap and isolated.** Read traffic never touches the owning services; a transfers outage
  does not block status reads of already-projected transfers.
- **Eventual consistency on reads.** The read model lags the saga by the consumer's processing delay.
  Acceptable for status display; not a source of truth for money.
- **One more consumer group set + table to operate** (`transfer_view`, `processed_messages`, four
  projection listeners).
- **springdoc must track Spring Boot.** springdoc 2.6.0 is incompatible with Boot 3.5 (a
  `ControllerAdviceBean` constructor removed in Spring 6.2 → `NoSuchMethodError`); the gateway pins
  springdoc **2.8.9**.

## Alternatives considered

- **Code-first springdoc (generate the contract from controllers).** Rejected: the published contract
  would drift with implementation details and refactors; spec-first keeps the contract a deliberate,
  reviewable artifact.
- **No gateway — clients call transfers/accounts directly.** Rejected: duplicates edge concerns across
  services, couples clients to internal service topology, and offers no single resilience or
  read-model seam.
- **Read-through to transfers for status.** Rejected: every read becomes a synchronous downstream call,
  re-coupling read availability to the owning service.
