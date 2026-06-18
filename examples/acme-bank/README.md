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

## How to Run the Full Stack (smoke path)

```bash
# 1. Start infrastructure
docker compose -f examples/acme-bank/compose.bank.yaml up -d

# 2. Start each service (separate terminals or use process manager)
gradle :examples:acme-bank:transfers:bootRun
gradle :examples:acme-bank:antifraud:bootRun
gradle :examples:acme-bank:accounts:bootRun
gradle :examples:acme-bank:notifications:bootRun

# 3. Initiate a transfer (requires a JWT from Keycloak)
curl -X POST http://localhost:8080/v1/transfers \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sourceAccountId":"acc-a","destinationAccountId":"acc-b","amount":"100.00","asset":"USD"}'
```

The saga will flow through all topics automatically. Monitor the Redpanda console at
`http://localhost:8088` to watch events flow.
