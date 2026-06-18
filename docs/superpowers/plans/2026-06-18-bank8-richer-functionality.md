# BANK-8: richer service functionality Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** Flesh out the skeletal services into a usable API: `accounts` gains an open/query/lifecycle REST surface (open account with double-entry opening deposit, get, derived balance, paged statement, freeze/close); `transfers` gains query endpoints (list by account/status, get one); the `gateway` contract extends with account operations and proxies `accounts` via a resilience4j `RestClient`, while serving transfer queries from its existing read model.

**Architecture:** Each service keeps its hexagonal shape. accounts adds a web in-adapter + an `OpenAccount` application command (StronglyConsistent) and read queries over the existing derived-balance ledger (NO materialization). The opening deposit is a real double-entry posting from a seeded bank equity account (Σ=0). The gateway stays the public edge; accounts/transfers expose internal-but-secured REST that the gateway proxies.

**Tech Stack:** Java 21, Spring Boot 3.5.6, acme-web/cqrs/persistence/security starters, Spring `RestClient` + Resilience4j, openapi-generator (gateway), Flyway, Testcontainers.

> Spec: `docs/superpowers/specs/2026-06-18-acme-bank-production-grade-design.md` §3. Builds on BANK-0..7.
> **Environment (every task):** work from `/Users/npden4ik/Projects/java-boilerplate`; system `gradle` (8.14) NEVER `./gradlew`; JDK 21; Docker up (Postgres/Redpanda cached). Bank service convention plugin id is `acme.bank-service-conventions` + `alias(libs.plugins.spring.boot)`. `gradle <module>:spotlessApply` before each commit. Mirror sibling code: `examples/acme-bank/{accounts,transfers,gateway}` for wiring/test patterns.

---

## Task 1: accounts domain — lifecycle + derived queries (TDD)

**Files:** `accounts/.../domain/Account.java` (lifecycle), `.../domain/Ledger.java` (port: add `balanceOf`, `entriesFor`), `.../domain/StatementLine.java` (new value type), unit tests `AccountTest`, `LedgerTest` (if a fake exists).

- [ ] **Step 1: failing test** `AccountTest`: `freeze()` moves OPEN→FROZEN; `close()` moves OPEN/FROZEN→CLOSED; `freeze()` on CLOSED throws `IllegalStateException`; a CLOSED/FROZEN account `isOperational()` is false (already used by BANK-6's posting guard). Reconstruction via existing factory preserves status.
- [ ] **Step 2: run, FAIL.**
- [ ] **Step 3:** Add `freeze()`, `close()` guarded transitions to `Account` (statuses OPEN/FROZEN/CLOSED already exist). Keep `isOperational()` = status == OPEN.
- [ ] **Step 4:** Define `StatementLine` value type: `{ Instant postedAt, String counterpartyAccountId, Money signedAmount, Money runningBalance }` (running balance is derived by folding entries in order — computed in the query service, not stored).
- [ ] **Step 5:** Extend the `Ledger` port with `Money balanceOf(AccountId)` (derived `SUM(amount)` of that account's entries) and `List<LedgerEntry> entriesFor(AccountId, Instant from, Instant to, int page, int size)`. Add the JPA implementations in `adapter/out/persistence/JpaLedger` using a derived `@Query` (`SELECT COALESCE(SUM(e.amount...))`) — NO materialized balance table (honors the standing constraint). For balance, sum the `MoneyAmount` value column WHERE asset matches the account's asset.
- [ ] **Step 6: run, PASS** — `gradle :examples:acme-bank:accounts:test --tests "*AccountTest"`.
- [ ] **Step 7: commit**
```bash
gradle :examples:acme-bank:accounts:spotlessApply
git add examples/acme-bank/accounts
git commit -m "feat(accounts): account lifecycle (freeze/close) + derived balance/statement ledger queries"
```

---

## Task 2: accounts — OpenAccount command with double-entry opening deposit (TDD)

**Files:** `accounts/.../application/OpenAccountCommand.java` (+ `OpenAccountHandler`), `accounts/.../domain/...` IBAN generation, migration `V4__system_account.sql` (seed a bank equity account), `OpenAccountIT.java`.

- [ ] **Step 1:** Migration `db/migration/postgresql/V4__system_account.sql` — seed a system equity account that funds opening deposits (so the ledger stays balanced): `INSERT INTO account(id, iban, status, asset) VALUES ('bank-equity','EQUITY-0000', 'OPEN', 'USD')` (match the real `account` columns — read V1). This account holds the negative of all deposits (its balance goes negative — it's the bank's equity/source).
- [ ] **Step 2: failing IT** `OpenAccountIT` (Postgres): send `OpenAccountCommand(ownerName, asset=USD, initialDeposit=Money 100.00 USD)` via the Pipeline; assert (a) an `account` row exists with a generated IBAN + status OPEN; (b) the new account's derived balance == 100.00 USD; (c) the ledger has Σ=0 for the opening posting (the equity account decreased by 100.00); (d) opening with no deposit → balance 0, no posting. Idempotency: a second send with the same client-supplied `requestId` does not double-open (anchor on a request id).
- [ ] **Step 3: run, FAIL.**
- [ ] **Step 4:** Implement `OpenAccountCommand` (StronglyConsistent — joins the cqrs TransactionMiddleware tx) + `OpenAccountHandler`:
  - generate an `AccountId` + a deterministic-ish IBAN (e.g. `ACME` + zero-padded sequence or a UUID-derived check — keep it simple, valid-shaped not real-MOD97 unless trivial).
  - persist the `Account` (OPEN).
  - if `initialDeposit > 0`: create a `Posting` (idempotency anchor id = `"open-" + accountId`) with two `LedgerEntry`: `+deposit` to the new account, `-deposit` to `bank-equity`. Reuse the existing `Posting` Σ=0 invariant + `Ledger.post(...)` from BANK-1. Same-asset invariant holds (deposit asset == account asset == equity asset).
  - return the new accountId + iban.
  - dedup: anchor the open on a client `requestId` (a `processed_messages`-style row or the posting PK) so a retry is idempotent.
- [ ] **Step 5: run, PASS** — `gradle :examples:acme-bank:accounts:test --tests "*OpenAccountIT"`.
- [ ] **Step 6: commit**
```bash
gradle :examples:acme-bank:accounts:spotlessApply
git add examples/acme-bank/accounts
git commit -m "feat(accounts): OpenAccount command — double-entry opening deposit from bank equity (Σ=0)"
```

---

## Task 3: accounts — REST in-adapter (TDD)

**Files:** `accounts/build.gradle.kts` (add acme-web + acme-security starters), `accounts/.../adapter/in/web/AccountController.java`, DTOs, `accounts/src/main/resources/application.yaml` (web + security), `AccountApiIT.java`.

- [ ] **Step 1:** Add `implementation(project(":starters:acme-web-spring-boot-starter"))` + `:starters:acme-security-spring-boot-starter` to `accounts/build.gradle.kts`. Add `spring.security` jwk-set-uri + server port to `application.yaml` (mirror transfers).
- [ ] **Step 2: failing IT** `AccountApiIT` (`webEnvironment=RANDOM_PORT`, Postgres; JWT via `spring-security-test` `jwt()` or a `@TestConfiguration` `JwtDecoder` stub like the gateway's). Cases:
  - `POST /v1/accounts` `{ownerName, asset, initialDeposit:{value,asset}}` → 201 `{accountId, iban, status:"OPEN"}`.
  - `GET /v1/accounts/{id}` → 200 view.
  - `GET /v1/accounts/{id}/balance` → 200 `{value:"100.00", asset:"USD"}` after an opening deposit.
  - `GET /v1/accounts/{id}/statement` → 200 paged lines (the opening credit), with `runningBalance`.
  - `POST /v1/accounts/{id}/freeze` → 200; subsequent posting to it would be rejected (covered by BANK-6 — here just assert status FROZEN via GET).
  - missing bearer → 401.
- [ ] **Step 3: run, FAIL.**
- [ ] **Step 4:** Implement `AccountController` (`@RestController`, problem+json via the web starter), mapping requests to the `OpenAccountCommand` + read queries (`Accounts.findById`, `Ledger.balanceOf`, `Ledger.entriesFor` → fold into `StatementLine`s with running balance, money as string DTOs). Freeze/close call a small `ChangeAccountStatus` command (StronglyConsistent) or a handler that loads, transitions, saves. All money rendered as exact decimal strings at asset scale (no float).
- [ ] **Step 5: run, PASS** — `gradle :examples:acme-bank:accounts:test --tests "*AccountApiIT"`.
- [ ] **Step 6: commit**
```bash
gradle :examples:acme-bank:accounts:spotlessApply
git add examples/acme-bank/accounts
git commit -m "feat(accounts): REST API — open/get/balance/statement/freeze/close (OAuth2, problem+json)"
```

---

## Task 4: transfers — query endpoints (TDD)

**Files:** `transfers/.../adapter/in/web/TransferController.java` (extend), `transfers/.../application/{GetTransfer,ListTransfers}.java`, repository query, `TransferQueryIT.java`.

- [ ] **Step 1: failing IT** `TransferQueryIT` (`RANDOM_PORT`, Postgres): seed two transfers (different source accounts/status via the persistence port or by initiating); `GET /v1/transfers?accountId=X` returns only X's; `GET /v1/transfers?status=COMPLETED` filters; `GET /v1/transfers/{id}` returns the full view; unknown id → 404 problem+json; missing bearer → 401; page/size honored.
- [ ] **Step 2: run, FAIL.**
- [ ] **Step 3:** Add a read query to the transfers persistence adapter (`TransferJpaRepository` derived/`@Query` methods: by source-or-destination accountId, by status, paged) and `GetTransfer`/`ListTransfers` application services. Extend `TransferController` with `GET /v1/transfers` (filters + paging) and `GET /v1/transfers/{id}`. Map the `Transfer` aggregate → a `TransferView` DTO (transferId, status, amount string, accounts, failureReason, timestamps). Keep the existing POST write path untouched.
- [ ] **Step 4: run, PASS** — `gradle :examples:acme-bank:transfers:test --tests "*TransferQueryIT"`.
- [ ] **Step 5: commit**
```bash
gradle :examples:acme-bank:transfers:spotlessApply
git add examples/acme-bank/transfers
git commit -m "feat(transfers): query endpoints — list by account/status (paged) + get by id"
```

---

## Task 5: gateway — extend contract with account ops + proxy accounts (TDD)

**Files:** `gateway/src/main/resources/openapi/bank-api.yaml` (+ account paths/schemas), `gateway/src/main/resources/static/openapi/bank-api.yaml` (keep the served copy in sync), `gateway/.../client/AccountsRestClient.java`, `gateway/.../web/AccountController.java` (implements generated `AccountsApi`), `gateway/.../application/...`, `AccountProxyIT.java`.

- [ ] **Step 1:** Extend `bank-api.yaml` with: `POST /v1/accounts`, `GET /v1/accounts/{id}`, `GET /v1/accounts/{id}/balance`, `GET /v1/accounts/{id}/statement` (tag `accounts` → generates `AccountsApi`), and schemas `OpenAccountRequest`, `AccountView`, `BalanceView` (= Money), `StatementPage`/`StatementLine`. Copy the same YAML into `static/openapi/` (the served contract — they MUST match; consider a build step or a test asserting equality).
- [ ] **Step 2: failing IT** `AccountProxyIT` (`RANDOM_PORT`, MockMvc + `jwt()`, accounts downstream faked via a `@Primary` `AccountsRestClient` stub or WireMock): `POST /v1/accounts` proxies → 201; `GET /v1/accounts/{id}/balance` proxies → 200; an accounts-side 404 propagates as 404 (not 503 — reuse the BANK-7 `DownstreamErrorHandler`); accounts-side 5xx/open-circuit → 503.
- [ ] **Step 3: run, FAIL** (no `AccountsApi` impl yet → generated interface unimplemented; controllers won't compile until added).
- [ ] **Step 4:** Implement `AccountsRestClient` (Spring `RestClient` to the accounts base URL, `@CircuitBreaker`+`@Retry` instance `accounts` with `ignore-exceptions: [HttpClientErrorException]` like the transfers client) + `AccountController implements AccountsApi` mapping generated DTOs ↔ the proxied responses. Add the resilience4j `accounts` instance to `application.yaml`. Reuse `DownstreamErrorHandler`/`GatewayUnavailableException` from BANK-7.
- [ ] **Step 5:** Add an `OpenApiServedContractTest` (or extend `OpenApiContractTest`) asserting the `static/openapi/bank-api.yaml` byte-equals the source `openapi/bank-api.yaml`, and `/v3/api-docs` operationIds ⊇ {createTransfer,getTransfer,listTransfers,openAccount,getAccount,getAccountBalance,getAccountStatement}.
- [ ] **Step 6: run, PASS** — `gradle :examples:acme-bank:gateway:test`.
- [ ] **Step 7: commit**
```bash
gradle :examples:acme-bank:gateway:spotlessApply
git add examples/acme-bank/gateway
git commit -m "feat(gateway): account operations — spec-first contract + resilience4j proxy to accounts"
```

---

## Task 6: full build + ADR

- [ ] **Step 1:** `gradle build` → BUILD SUCCESSFUL.
- [ ] **Step 2:** ADR `docs/decisions/0019-account-lifecycle-and-queries.md` — decisions: opening deposit as a double-entry posting from a seeded bank-equity account (keeps Σ=0, no special-case money creation); balance + statement derived (no materialization, restating the standing constraint and the latency trade-off); gateway proxies accounts (edge composition) while serving transfer reads from its own projection; query endpoints separate from the saga write path. Alternatives + consequences.
- [ ] **Step 3: commit**
```bash
git add docs/decisions/0019-account-lifecycle-and-queries.md
git commit -m "docs: ADR 0019 account lifecycle, double-entry opening deposit, derived queries"
```

---

## Done criteria for BANK-8

- accounts: open (with Σ=0 opening deposit), get, derived balance, paged statement (running balance), freeze/close — all over REST, OAuth2-secured, problem+json, float-free money.
- transfers: list (by account/status, paged) + get one.
- gateway: spec-first contract extended with account ops; proxies accounts via resilience4j (4xx propagated, 5xx/open-circuit→503); served contract matches source; drift test covers all operations.
- `gradle build` green; ADR 0019 written.

---

## Self-review notes

- **Spec coverage:** §3 accounts open/get/balance/statement/freeze (T1-T3) ✓; transfers list/get (T4) ✓; gateway aggregation (T5) ✓.
- **Type consistency:** `OpenAccountCommand`/`OpenAccountHandler`; `Ledger.balanceOf`/`entriesFor`; `StatementLine{postedAt,counterparty,signedAmount,runningBalance}`; `AccountsRestClient` mirrors `RestTransfersClient`; reuse `DownstreamErrorHandler`/`GatewayUnavailableException` from BANK-7; generated `AccountsApi`/`AccountView`/`OpenAccountRequest`.
- **No placeholders:** IBAN generation kept deliberately simple (valid-shaped, not real MOD-97) — noted, acceptable for the example.
- **Standing constraints honored:** derived balance/statement, NO materialized balance; double-entry Σ=0 for the opening deposit; no float (Money strings).
- **Risk:** the equity-account seed must use the real `account` columns (read V1 first). `balanceOf` SUM must filter by asset + match the `MoneyAmount` column names. Statement running-balance is computed in-query by ordered fold (page boundaries: document that running balance is within-page unless computed from an opening balance — compute from account opening for correctness, or note the per-page caveat).
