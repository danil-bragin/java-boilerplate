# acme-bank — Choreographed Money-Movement Saga

`acme-bank` is a reference implementation of a **choreographed saga** for money transfers, built on
Spring Boot 3.5 / Spring Modulith, Apache Kafka (Avro), and PostgreSQL.  Four independent services
communicate exclusively via Avro events on Redpanda topics — there is no orchestrator.

---

## Architecture: Saga Choreography

```
REST call
   │
   ▼
┌────────────┐  transfer-requested  ┌─────────────┐  transfer-screened
│ transfers  │ ──────────────────►  │  antifraud  │ ─────────────────►─┐
└────────────┘                      └─────────────┘                     │
      ▲  ◄── transfer-completed ──────────────────────────────────────  │
      │  ◄── transfer-failed ───────────── (rejected) ◄────────────────┘
      │                                                                  │
      │  posting-requested                                               │
      │ ────────────────────────────────────────────────────────────►   │
      │                                           ┌──────────────┐      │
      │                                           │   accounts   │      │
      │  ◄── ledger-posted ───────────────────────┤              │      │
      │  ◄── posting-rejected ────────────────────┘              │
      │                                                           │
      │  transfer-completed / transfer-failed                     │
      │ ─────────────────────────────────────────────────────►   │
      │                                      ┌───────────────┐   │
      │                                      │ notifications │◄──┘
      │                                      └───────────────┘
```

### Topic Convention

| Topic                | Producer    | Consumer(s)                 |
|----------------------|-------------|-----------------------------|
| `transfer-requested` | transfers   | antifraud                   |
| `transfer-screened`  | antifraud   | transfers                   |
| `posting-requested`  | transfers   | accounts                    |
| `ledger-posted`      | accounts    | transfers                   |
| `posting-rejected`   | accounts    | transfers                   |
| `transfer-completed` | transfers   | notifications               |
| `transfer-failed`    | transfers   | notifications               |

---

## Services

### transfers
- REST API (`POST /v1/transfers`, `GET /v1/transfers/{id}`)
- Coordinates the saga: emits `TransferRequested`, consumes `TransferScreened`/`LedgerPosted`/`PostingRejected`, emits downstream events
- Inbox dedup on each consumed topic; outbox via Spring Modulith event externalization

### antifraud
- Consumes `TransferRequested`, applies risk rules, emits `TransferScreened`
- Implemented in BANK-3

### accounts
- Consumes `PostingRequested`, executes double-entry ledger posting, emits `LedgerPosted` or `PostingRejected`
- Existing domain: `PostTransferCommand` via CQRS pipeline; posting is idempotent by `transfer_id`

### notifications
- Consumes terminal events (`TransferCompleted`, `TransferFailed`)
- Persists a notification row; mock delivery via `LoggingDeliveryAdapter`
- No outbox (terminal consumer — emits nothing)

---

## Hexagonal Structure (per service)

```
src/main/java/com/acme/bank/<service>/
  domain/           — pure domain model (no Spring, no Avro)
  application/      — use-cases / application services
  adapter/
    in/
      messaging/    — @KafkaListener + Inbox dedup
      web/          — REST controllers (transfers only)
    out/
      messaging/    — Avro mappers + EventExternalizationConfiguration
      persistence/  — JPA entities + repositories
  config/           — Spring @Configuration (Jackson, externalization, etc.)
```

---

## Automated Verification

Each saga hop is covered by a per-hop Avro IT that runs against real Redpanda + PostgreSQL
(Testcontainers):

| Test | Module | What it proves |
|------|--------|----------------|
| `TransferExternalizationIT` | transfers | REST initiation → `TransferRequested` Avro on topic |
| `TransferAdvanceIT` | transfers | `TransferScreened` → `PostingRequested`; `LedgerPosted` → `TransferCompleted`; rejections → `TransferFailed` |
| `ScreeningIT` | antifraud | `TransferRequested` → `TransferScreened`; risk rules; inbox dedup |
| `PostingFlowIT` | accounts | `PostingRequested` → `LedgerPosted` (Σ=0); insufficient funds → `PostingRejected`; inbox dedup |
| `NotificationIT` | notifications | `TransferCompleted`/`TransferFailed` → `notification` row; inbox dedup |

Run all tests: `gradle build`

### Functional-contract assertions

The per-service API ITs (`AccountApiIT`, `TransferApiIT`, `TransferQueryIT`, gateway
`TransferControllerIT` / `AccountProxyIT`) double as black-box functional-contract tests: success
shapes, **idempotency replay** (a repeated `Idempotency-Key` replays the same 2xx body), **validation
400** as `application/problem+json`, **404** problem+json with the right content type, **401** for
unauthenticated calls, and paging clamps.

### End-to-end tests

The `examples/acme-bank/e2e` module is a **test-only** module that brings up the *entire*
`compose.bank.yaml` stack via Testcontainers `ComposeContainer` (`withLocalCompose(true)`,
`withBuild(true)` — it builds the five service images from the per-service Dockerfiles), obtains a
**real Keycloak token** (password grant on `bank-gateway`), and drives the saga through the gateway's
public HTTP edge — exactly what ships. Scenarios:

| Scenario | Proves |
|----------|--------|
| happy-path (`TransferSagaE2eTest`) | open two accounts → `POST /v1/transfers` → saga settles to **COMPLETED**; source/destination balances moved; destination **statement** shows the credit |
| rejected (`SagaScenariosE2eTest`) | an amount above the antifraud `AMOUNT_LIMIT` (10000 USD) → **FAILED**, failure reason surfaced, **no money moved** |
| idempotency (`SagaScenariosE2eTest`) | same `Idempotency-Key` + body → one transfer, source debited **once** (no double-spend) |
| auth-negative (`SagaScenariosE2eTest`) | no bearer / a garbage bearer → **401** at the edge |

Run it (Docker required):

```bash
gradle bankJars                                   # the ComposeContainer builds images from these jars
gradle :examples:acme-bank:e2e:e2eTest            # the on-demand entrypoint
```

The e2e is **`@Tag("e2e")` and EXCLUDED from the default `gradle build`** (it needs image pulls/builds
of Keycloak + otel-lgtm + the service images, so it is heavy and slow on a cold cache). The default
build still compiles the e2e module's tests (`compileTestJava`), so the wiring is verified on every
PR; the full run goes through the dedicated nightly / `workflow_dispatch` CI job. One stack is shared
per test class (`@TestInstance(PER_CLASS)`) to bound startup cost.

---

## Run the Whole System (one command)

The full stack — all five services plus Postgres (one DB per service), Redpanda + Schema Registry,
Keycloak (seeded `bank` realm), Redis (shared idempotency), and otel-lgtm (Grafana traces/metrics/logs)
— runs from `compose.bank.yaml`. Services are containerized from per-service Dockerfiles
(`eclipse-temurin:21-jre` + `bootJar`), so build the jars first:

```bash
gradle bankJars
docker compose -f examples/acme-bank/compose.bank.yaml up --build
```

> The Keycloak (`quay.io/keycloak/keycloak:26.0`) and otel-lgtm (`grafana/otel-lgtm`) images are large;
> the first `up` may take a while to pull them.

### URLs

| Service | URL |
|---------|-----|
| Gateway (the only published edge) | http://localhost:8080  — Swagger UI at `/swagger-ui.html` |
| Keycloak | http://localhost:8082 (admin/admin) |
| Grafana (traces/metrics/logs) | http://localhost:3000 |

### Get a token and drive a transfer

```bash
# Fetch an access token for alice (password grant on the public bank-gateway client).
# Works straight from the host: the issuer is pinned to a constant URL (see the issuer rule below).
TOKEN=$(curl -s \
  -d 'client_id=bank-gateway&username=alice&password=alice&grant_type=password' \
  http://localhost:8082/realms/bank/protocol/openid-connect/token | jq -r .access_token)

curl -X POST http://localhost:8080/v1/transfers \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"sourceAccountId":"acc-a","destinationAccountId":"acc-b","amount":"100.00","asset":"USD"}'
```

Open Grafana → Explore → Tempo and search the trace: a single trace spans
**gateway → transfers → Kafka → accounts** (the `traceparent` header rides the Avro records — see
ADR-0020 and `TransferExternalizationIT`).

### The single-issuer-URL rule (avoids the classic Keycloak 401)

Every service validates JWTs against **one** issuer, the in-network URL
`http://keycloak:8080/realms/bank` (`OAUTH2_ISSUER_URI`). To make that work for callers both inside
the compose network **and** on the host, Keycloak's `KC_HOSTNAME` is set to the **full URL**
`http://keycloak:8080` (Keycloak hostname-v2). With a full-URL hostname the frontend AND backchannel
URLs are pinned to that constant, so **every** minted token carries `iss=http://keycloak:8080/realms/bank`
regardless of which host the token endpoint was called from. A token fetched from the host via the
mapped port (`localhost:8082`) is therefore accepted by the services too — which is exactly what the
e2e relies on (it fetches the token from the host). This single-issuer rule is the one thing that
trips up most Keycloak-behind-a-gateway setups; pinning a constant full-URL issuer is the robust fix.

### Scale horizontally

```bash
docker compose -f examples/acme-bank/compose.bank.yaml up --build \
  --scale transfers=2 --scale accounts=2
```

**Why this is safe at >1 replica:**

- **transfers / accounts** — each consumed topic has **per-replica inbox dedup**, and posting is
  **DB-anchored** (the double-entry `Posting` carries an idempotency anchor with a unique constraint).
  Two replicas processing the same event converge to the same single posting; duplicates are no-ops.
- **gateway** — REST idempotency is shared across replicas via **Redis** (`RedisIdempotencyStore`,
  activated by `SPRING_DATA_REDIS_HOST=redis`). Without it, a retry routed to a different gateway
  replica would re-execute; with it, the reservation/response is visible to every replica (SETNX +
  TTL). Locally (no Redis) the gateway falls back to the in-memory store.

### Local `bootRun` (no containers)

Every service still runs locally with sensible localhost defaults (the env vars above all have
`${VAR:localhost-default}` fallbacks):

```bash
docker compose -f examples/acme-bank/compose.bank.yaml up -d postgres redpanda keycloak observability redis
gradle :examples:acme-bank:gateway:bootRun   # + transfers / accounts / antifraud / notifications
```
