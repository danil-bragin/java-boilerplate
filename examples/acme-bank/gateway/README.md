# gateway — spec-first edge service

The `gateway` is the acme-bank edge (BFF). It is **spec-first**: the hand-written OpenAPI 3.0.3
contract at `src/main/resources/openapi/bank-api.yaml` is the single source of truth. The
`org.openapi.generator` plugin (generator `spring`, `interfaceOnly`) turns the contract into Spring
`*Api` server interfaces; controllers `implements` those interfaces, so any contract operation
without an implementation is a **compile error**.

## Spec-first flow

1. Edit the contract: `src/main/resources/openapi/bank-api.yaml`.
2. Regenerate interfaces: `gradle :examples:acme-bank:gateway:openApiGenerate` (also runs automatically
   before `compileJava`). Generated sources land in `build/generated/openapi/...` and are added to the
   main source set.
3. Implement the new/changed operation on the controller (it `implements TransfersApi`). A missing
   method will not compile.
4. Keep the served copy in sync: `src/main/resources/static/openapi/bank-api.yaml` is the verbatim
   contract served to swagger-ui.

## swagger-ui

swagger-ui renders the **hand-written contract** (not a code-introspected guess):

- Contract: `GET /openapi/bank-api.yaml`
- swagger-ui: `GET /swagger-ui.html` (configured via `springdoc.swagger-ui.url=/openapi/bank-api.yaml`)
- Code-introspected docs (used by the drift guard): `GET /v3/api-docs`

`OpenApiContractTest` is the drift guard: it asserts the served `/v3/api-docs` exposes every contract
operation and that the hand-written contract is served verbatim.

## CQRS read model

`GET /v1/transfers/{id}` and the list endpoint are served from a **read model** (`transfer_view`),
not from the owning service. `TransferStatusProjection` builds it by consuming the saga's Avro events
(`transfer-requested`, `transfer-screened`, `transfer-completed`, `transfer-failed`). Each listener is:

- **inbox-deduped** — one `processed_messages` row per `(listener, transferId)` (at-least-once safe);
- **rank-guarded** — a status only advances when the incoming rank ≥ the stored rank, so redelivered
  or out-of-order events never regress a transfer's status.

This is a read-model projection, not a money optimization: the gateway holds no balances and enforces
no money invariants — those live in the owning services.

## Resilience

`POST /v1/transfers` forwards to the downstream `transfers` service through `RestTransfersClient`, a
Spring `RestClient` wrapped with Resilience4j `@CircuitBreaker` + `@Retry` (instance `transfers`).
When the circuit is open or retries are exhausted the fallback raises `GatewayUnavailableException`,
which the problem+json handler renders as **503**.

## Edge concerns

OAuth2 resource-server (JWT), `Idempotency-Key`, rate-limiting, and RFC 9457 problem+json are provided
by the acme-* edge starters. Docs paths (`/openapi/**`, `/swagger-ui/**`, `/v3/api-docs`) are permitted
without authentication.
