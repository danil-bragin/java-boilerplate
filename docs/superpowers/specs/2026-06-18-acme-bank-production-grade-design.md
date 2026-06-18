# acme-bank → production-grade: gateway, spec-first API, full stack, e2e — Design

> Extends `2026-06-18-acme-bank-design.md`. Adds the missing production surface the saga skeleton lacks: a real API gateway, spec-first OpenAPI as the entry contract, richer service functionality, a fully-deployable scaled+observable stack with Keycloak, and functional + end-to-end tests. Builds on BANK-0..6 (the choreographed saga is complete and green).

## 0. Why

The saga is correct but thin. Gaps the user called out:
- No **gateway** — clients hit `transfers` directly; no single entry, no edge aggregation.
- No **spec-first OpenAPI** — the API is code-first afterthought; need contract-first with generated server stubs + published swagger-ui.
- Service functionality is **skeletal** — accounts can't be opened/listed/queried via API; transfers can't be listed.
- No **full deployment** — `compose.bank.yaml` exists but services aren't imaged, not scaled, Keycloak realm not seeded, observability not wired end-to-end.
- No **functional / e2e tests** — only per-hop ITs; no black-box test of the whole stack through the gateway with a real token.

## 1. Phases

| Phase | Deliverable |
|---|---|
| **BANK-7** | `gateway` service + spec-first OpenAPI (openapi.yaml → generated API interfaces → controllers) + transfer-status read model (consumes terminal events) + RestClient→transfers (resilience4j) + swagger-ui |
| **BANK-8** | richer service functionality: accounts (open/list/get/balance/statement/freeze), transfers (list/history), gateway aggregation |
| **BANK-9** | full deployable stack: `bootBuildImage` all services, `compose.bank.yaml` with every service + per-service Postgres schema + Redpanda+SR + seeded Keycloak realm + otel-lgtm wired + scaling (replicas) |
| **BANK-10** | functional tests (per-service black-box) + full-stack **e2e** (drive the saga through the gateway with a real Keycloak token → COMPLETED + ledger Σ=0 + notification) |

Each phase: plan → subagent execute → adversarial review → fix → next. Non-stop.

## 2. BANK-7 — gateway + spec-first OpenAPI

### 2.1 Spec-first contract
- New module `examples/acme-bank/gateway`.
- `src/main/resources/openapi/bank-api.yaml` — the **hand-written** OpenAPI 3.1 contract. The source of truth. Defines:
  - `POST /v1/transfers` (Idempotency-Key header, `CreateTransferRequest{sourceAccountId,destinationAccountId,amount:{value,asset},reference?}` → 202 `TransferAccepted{transferId,status}`).
  - `GET /v1/transfers/{id}` → 200 `TransferView{transferId,status,amount,source,destination,failureReason?,createdAt,updatedAt}`.
  - `GET /v1/transfers?accountId=&status=&page=` → 200 `TransferPage`.
  - `POST /v1/accounts` (open), `GET /v1/accounts/{id}`, `GET /v1/accounts/{id}/balance`, `GET /v1/accounts/{id}/statement` (BANK-8 fills handlers; contract defined here).
  - `components/schemas` for Money (`{value:string, asset:string}`), problem+json (RFC 9457) responses, error codes.
  - `securitySchemes`: OAuth2 bearer (Keycloak).
- **Codegen:** `org.openapi.generator` Gradle plugin, `generatorName=spring`, `interfaceOnly=true`, `useSpringBoot3=true`, `useTags=true`, library `spring-boot`, `documentationProvider=none`. Generates `*Api` interfaces + DTOs into `build/generated`. Controllers `implements TransfersApi, AccountsApi`. Generated sources on the compile path; NOT committed.
- Add `openapi-generator` version to the catalog. The generated DTOs are the wire model; map to/from domain at the controller boundary.
- **swagger-ui:** springdoc-openapi-starter-webmvc-ui serves `/swagger-ui.html`; point springdoc at the static `bank-api.yaml` (`springdoc.swagger-ui.url=/openapi/bank-api.yaml`) so the published UI IS the hand-written contract (not a code-introspected one) — true spec-first.
- **Contract drift guard:** an `OpenApiContractTest` boots the app and asserts every generated `*Api` operationId is implemented (the controllers compile against the interfaces, so drift = compile failure — the test additionally asserts the served `/v3/api-docs` matches operation set). CI runs it.

### 2.2 Gateway responsibilities (edge, hexagonal)
- **Adapter-in (web):** the generated-interface controllers. All the acme-web edge starters: OAuth2 resource server (Keycloak JWT), `IdempotencyFilter`, rate-limit (acme-ratelimit), problem+json, request logging/tracing.
- **Application:** thin orchestration — `SubmitTransfer` (forwards to transfers via RestClient), `GetTransfer`/`ListTransfers` (reads the local projection), account proxies (BANK-8).
- **Adapter-out:**
  - `TransfersRestClient` — Spring `RestClient` to the `transfers` service, wrapped in resilience4j (circuit breaker + retry + timeout + bulkhead). On open circuit → 503 problem+json.
  - `TransferStatusProjection` — a **read model**: the gateway consumes the saga's terminal+intermediate events (`transfer-requested`, `transfer-screened`, `transfer-completed`, `transfer-failed`) via Avro + inbox dedup, and upserts a `transfer_view` table (transferId, status, amount, accounts, reason, timestamps). `GET /v1/transfers/{id}` serves from this projection (CQRS read side) — so the gateway answers status without round-tripping transfers. Idempotent upsert (last-write by status rank, or by event timestamp).
- **No domain logic** — the gateway owns no money invariants; it's an edge + read model. ArchUnit: no JPA in a `domain` (there is none); the projection is an adapter concern.

### 2.3 Status projection event model
- Inbound events already exist (Avro). Add a gateway consumer per type, inbox-deduped (`Inbox.firstTime("gateway-projection", transferId+eventType)`).
- `transfer_view.status` transitions monotonically (REQUESTED→SCREENING→APPROVED/POSTING→COMPLETED/FAILED). Use a status rank to ignore out-of-order/older events (don't regress COMPLETED to REQUESTED on redelivery).

## 3. BANK-8 — richer functionality

### accounts (deepen the core)
- `POST /v1/accounts` — open: `OpenAccount{ownerName, asset, initialDeposit?}` → creates an account (status OPEN, generates IBAN), optionally an opening ledger entry funded from a system/equity account (double-entry preserved — opening balance is a posting from a bank equity account, Σ=0). New command `OpenAccountCommand` (StronglyConsistent).
- `GET /v1/accounts/{id}` → account view (id, iban, owner, status, asset).
- `GET /v1/accounts/{id}/balance` → derived balance (SUM ledger entries — already the model; expose it). Money string.
- `GET /v1/accounts/{id}/statement?from=&to=` → paged ledger entries (date, counterparty, amount signed, running balance derived).
- `POST /v1/accounts/{id}/freeze` / `/close` — status transitions (enforced by BANK-6's operational check on postings).
- Domain: `Account` gains lifecycle methods (`freeze`, `close`, guard transitions); `Ledger` gains `entriesFor(accountId, range)` + `balanceOf(accountId)` (derived, no materialization — per the user's standing constraint).

### transfers (query side)
- `GET /v1/transfers?accountId=&status=` — list/paginate transfers (read from the transfers DB; this is the write-side's own query, distinct from the gateway projection).
- `GET /v1/transfers/{id}` — full transfer view.
- Keep the saga write path unchanged.

### gateway aggregation
- `GET /v1/accounts/{id}` on the gateway proxies accounts (RestClient + resilience4j), `GET /v1/transfers/*` serves the projection. Demonstrates edge composition.

## 4. BANK-9 — full deployable stack (scaled + observable + Keycloak)

### Images
- Each service: `bootBuildImage` (Paketo buildpacks, no Dockerfile) → `acme-bank/<service>:local`. A Gradle task `bankImages` builds all.

### compose.bank.yaml (extended)
- **Infra:** Postgres 16 (one instance, a schema or database per service: `accounts`, `transfers`, `antifraud`, `notifications`, `gateway`), Redpanda + Schema Registry (8081), **Keycloak** (8082) with a seeded realm, **otel-lgtm** (Grafana 3000 / Tempo / Prometheus / Loki) for traces+metrics+logs.
- **Services:** gateway (8080, the only published edge), transfers, accounts, antifraud, notifications — each `depends_on` healthchecks (Postgres, Redpanda, Keycloak), each exports OTLP to otel-lgtm (`OTEL_EXPORTER_OTLP_ENDPOINT`), each `OAUTH2_ISSUER_URI` → Keycloak realm.
- **Scaling:** `deploy.replicas` (compose `--scale transfers=2 accounts=2`) to show horizontal scale; the **idempotency/inbox dedup + DB-anchored posting** make services safe under multiple replicas (this is WHY BANK-6 hardened the idempotency store — note: in-process store is per-replica; document that a shared store is needed for cross-replica idempotency at the gateway, and either (a) wire acme-cache Redis-backed `IdempotencyStore` for the gateway, or (b) document the limitation. Choose (a) — implement a `RedisIdempotencyStore` in acme-web behind `@ConditionalOnClass(RedisTemplate)` so scaled gateways share idempotency). Add Redis to compose.
- Healthchecks: actuator `/actuator/health` (liveness/readiness groups).

### Keycloak realm seed
- `examples/acme-bank/keycloak/realm-bank.json` — realm `bank`, client `bank-gateway` (confidential or public for the demo), client `bank-swagger` (public, for swagger-ui auth-code), realm roles (`customer`, `teller`), two seeded users (`alice`/`alice`, `teller`/`teller`) with roles. Imported via Keycloak `--import-realm` mount.
- Services validate `iss` = `http://keycloak:8082/realms/bank` (internal) — document the host/container issuer mismatch caveat and use a fixed `KC_HOSTNAME`.

### Observability
- All services already depend on acme-observability (Micrometer OTel → OTLP). Point at otel-lgtm. README: open Grafana, see the transfer trace span the gateway→transfers→Kafka→accounts hops (trace context propagated through Kafka headers — verify the outbox/consumer propagate W3C traceparent; if not, that's a fix in acme-messaging/acme-outbox).
- A `docs` section + screenshots-path placeholder in the README showing the distributed trace and the dashboards.

## 5. BANK-10 — functional + e2e tests

### Functional (per-service, black-box)
- For gateway, accounts, transfers: `@SpringBootTest(webEnvironment=RANDOM_PORT)` with Testcontainers (Postgres + Redpanda + a mock/stub JWT decoder via `spring-security-test` `jwt()` or a static JWKS) driving the REAL HTTP API (RestClient/TestRestClient), asserting status codes, problem+json shapes, idempotency replay, validation errors, OpenAPI conformance. These are functional (API-contract) not unit.

### End-to-end (full stack)
- A dedicated module `examples/acme-bank/e2e` (no main code, test-only).
- Uses Testcontainers `ComposeContainer` (or `DockerComposeContainer`) to bring up the **entire** `compose.bank.yaml` (all services + infra + Keycloak), OR a programmatic Testcontainers network wiring the built images. Prefer `ComposeContainer(compose.bank.yaml)` so the e2e tests exactly what's shipped.
- **Scenario (happy path):**
  1. Obtain a real access token from Keycloak (`alice`) via the token endpoint.
  2. `POST /v1/accounts` to open source + dest accounts (funded).
  3. `POST /v1/transfers` (with Idempotency-Key + bearer) → 202, capture transferId.
  4. Await (Awaitility, ~60s) `GET /v1/transfers/{id}` → status COMPLETED (the projection reflects the full saga: requested→screened→posting→ledger-posted→completed).
  5. Assert: source balance debited, dest credited (`GET .../balance`), ledger Σ=0, a notification row exists (query notifications, or assert via its API if exposed).
- **Scenario (rejected):** a transfer above the antifraud limit → status FAILED, reason surfaced, no ledger movement.
- **Scenario (idempotency):** same Idempotency-Key twice → one transfer, 202 replayed.
- **Auth negative:** no/invalid token → 401; wrong scope → 403.
- These run in a dedicated CI job (heavier; can be `@Tag("e2e")` and excluded from the fast build, run on demand / nightly).

## 6. Cross-cutting

- **CI:** add (a) schema-registry compat gate (imflog/avro plugin `testSchemasTask`), (b) the `bankImages` build, (c) the e2e job (tagged). Document in README.
- **Trace propagation through Kafka** — verify/fix W3C traceparent flows producer→consumer (acme-messaging/acme-outbox). If broken, fix as part of BANK-9.
- **No DB materialization** for balances stays in force (derived SUM). The gateway projection is a READ MODEL for transfer *status* (not money) — distinct from balance, allowed (CQRS read side over events, not a money optimization).
- **ADRs:** 0018 spec-first OpenAPI + gateway/BFF + read-model projection; 0019 deployable stack (Keycloak realm, scaling, shared idempotency store, observability); 0020 e2e strategy (ComposeContainer, real-token).

## 7. Out of scope (still)
- payments / external rails (SWIFT/Visa), FX/multi-currency, workers/KYC — documented future extensions.
- Kubernetes/Helm (compose is the deploy target; note k8s as a future step).
- GraalVM native, Oracle live run (reference config remains).

## 8. Done criteria
- Spec-first OpenAPI contract drives generated server interfaces; swagger-ui serves the hand-written contract.
- gateway is the single edge with OAuth2 + idempotency + rate-limit + resilience4j + a CQRS status projection.
- accounts/transfers have real query+command surface (open/list/balance/statement).
- `docker compose -f compose.bank.yaml up` brings the whole system up, scaled, observable (Grafana traces), authenticated by Keycloak.
- e2e tests drive the full saga through the gateway with a real token and assert end-state (COMPLETED, balances, ledger Σ=0, notification).
- `gradle build` green; e2e tagged job green.
