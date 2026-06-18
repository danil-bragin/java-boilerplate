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
# NOTE the issuer caveat below — fetch/drive from the host only if you mirror the issuer host.
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
`http://keycloak:8080/realms/bank` (`OAUTH2_ISSUER_URI`). `KC_HOSTNAME=keycloak` pins the token's
`iss` claim to that same URL. Therefore a token must be fetched from **inside** the compose network
to be accepted by the services — a token fetched from the host via `http://localhost:8082` carries
`iss=http://localhost:8082/...` and will be **rejected** (401) by the services. For host-side e2e,
fetch the token from within the network (e.g. `docker compose exec gateway ...` or add a host alias),
or run the e2e from a container on the same network. This single-issuer rule is the one thing that
trips up most Keycloak-behind-a-gateway setups.

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
