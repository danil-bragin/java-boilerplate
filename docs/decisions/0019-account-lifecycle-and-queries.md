# 0019 — Account Lifecycle, Double-Entry Opening Deposit, Derived Queries

**Date:** 2026-06-18
**Status:** Accepted

## Context

BANK-0..7 built the money-movement core (transfers saga, Avro outbox, antifraud consumer) and a
spec-first BFF gateway (ADR-0018). The `accounts` service, however, was still skeletal: it held the
`Account` aggregate, the double-entry `Posting`/`LedgerEntry` model with its Σ=0 invariant
(ADR-0014-era ledger), and a single inbound message handler that posted transfers. It had **no REST
surface**: you could not open an account, read its balance, list its transactions, or freeze it. The
`transfers` service could create and report a single transfer but could not answer "show me this
account's transfers". The gateway only proxied transfer writes.

BANK-8 fleshes these out into a usable API:

1. `accounts` gains open / get / derived-balance / paged-statement / freeze / close over OAuth2-secured,
   problem+json REST.
2. `transfers` gains query endpoints (list by account/status, get one).
3. The gateway extends its contract with account operations and proxies `accounts` via a resilience4j
   `RestClient`, while continuing to serve transfer reads from its own CQRS projection.

Two questions dominated the design: **how does money enter a brand-new account**, and **where does an
account's balance live**.

## Decision

### Opening deposit is a real double-entry posting from a seeded bank-equity account (Σ=0)

There is no special "money creation" path. A funded account-open books a normal `Posting` (idempotency
anchor `"open-" + accountId`) with two entries: `+deposit` credited to the new account and `-deposit`
debited from a **seeded system account `bank-equity`** (migration `V5__system_account.sql`). The
posting therefore satisfies the existing Σ=0 invariant and the same-asset invariant exactly like any
transfer — the equity account simply runs a negative balance representing the bank's funding source.
A zero/absent deposit opens the account with no posting at all (balance 0).

This keeps **one** money-movement primitive (the balanced posting) instead of a privileged mint path
that would sit outside the ledger invariant and have to be audited separately.

### Balance and statement are DERIVED, never materialized (standing constraint restated)

`Ledger.balanceOf(accountId)` is a `SELECT COALESCE(SUM(amount), 0)` over the account's `ledger_entry`
rows, filtered by the account's asset. The statement is the same entries, time-ordered, folded into a
running balance in the query service. There is **no materialized balance column and no balance table** —
this is a hard, standing constraint: the ledger is the single source of truth, balances are a pure
function of it, and there is no second value to keep in sync (no dual-write, no drift). A `posted_at`
column (migration `V4`) was added to `ledger_entry` so statements can order and time-bound entries.

Running balance is computed **correctly across pages**: for a requested page, the query seeds the fold
from `balanceBefore(accountId, firstEntryPostedAt)` — the SUM of all entries strictly before the page's
first returned entry — then folds the page in order. The running balance on a line is thus the true
account balance as of that line, not a within-page artifact.

The trade-off is read latency/cost: a balance is an aggregate query and a statement page does a bounded
scan plus a counterparty-resolution query, rather than an O(1) column read. For this example's volumes
that is the right trade — correctness and a single source of truth over micro-optimized reads. A future
optimization (a periodically-snapshotted opening balance, still derived) is left open.

### Account lifecycle on the aggregate; status changes via a StronglyConsistent command

`Account` owns the guarded transitions `freeze()` (OPEN→FROZEN) and `close()` (OPEN/FROZEN→CLOSED);
illegal transitions throw. `isOperational()` (status == OPEN) is what BANK-6's posting guard already
consults, so a frozen/closed account is automatically rejected as a posting source/destination. The
REST layer drives these through a small `ChangeAccountStatusCommand` (and `OpenAccountCommand`) on the
cqrs Pipeline, both marked `StronglyConsistent` so the `TransactionMiddleware` wraps account row,
idempotency anchor, and opening posting in one atomic transaction.

Opening is idempotent on a client-supplied request id via an `open_request` anchor table (PK-guarded,
flushed) — a retry returns the already-opened account rather than opening a second one, mirroring the
posting idempotency anchor.

### Query endpoints are separate from the write path

`transfers` query endpoints (`GET /v1/transfers?accountId=&status=`, `GET /v1/transfers/{id}`) read the
`Transfer` aggregate via new repository queries and map to a `TransferView` DTO; the saga write path
(`POST`) is untouched. accounts' read queries (`Ledger.balanceOf`/`entriesFor`, `Accounts.findById`)
are likewise read-only and do not enter the command pipeline.

### Gateway proxies accounts; serves transfer reads from its own projection

The gateway's contract (`bank-api.yaml`, served copy kept **byte-identical** and asserted so by
`OpenApiContractTest`) gains `openAccount`/`getAccount`/`getAccountBalance`/`getAccountStatement`,
generating an `AccountsApi` the controller `implements` (missing op ⇒ compile error). A new
`RestAccountsClient` (resilience4j instance `accounts`, `ignore-exceptions: HttpClientErrorException`)
proxies to the accounts service, mirroring `RestTransfersClient`. A downstream **4xx propagates
unchanged** (a 404 stays a 404 via the BANK-7 `DownstreamErrorHandler`); a downstream **5xx / connection
failure / open circuit becomes 503** (`GatewayUnavailableException`). Transfer reads, by contrast,
continue to be served from the gateway's own CQRS status projection (ADR-0018) — the gateway composes
account data at the edge but does not fan out for transfer status on every read.

### Money is always an exact decimal string at the asset's scale

Every monetary field on the wire (balance, statement amounts, transfer amount) is rendered as an exact
decimal string at the asset scale (e.g. `"100.00"`), never a JSON number/float — preserving the
no-float guarantee of `com.acme.money.Money` end to end.

## Alternatives considered

- **Materialized balance column/table updated on each posting.** Rejected: introduces a second source
  of truth that must be transactionally kept in lockstep with the ledger (dual-write, reconciliation,
  drift risk) for an O(1) read win that this example does not need. Violates the standing constraint.
- **A privileged "mint"/credit operation for opening deposits** that writes a single positive entry.
  Rejected: it would break the Σ=0 ledger invariant and create an unbalanced, unauditable money source.
  The seeded equity account keeps every movement balanced.
- **Within-page-only running balance** (start each page's fold at 0). Rejected as incorrect/misleading;
  computing the opening balance from the ledger is cheap and gives a true running balance per line.
- **Gateway fanning out to accounts AND transfers for all reads.** Partially rejected: transfer reads
  stay on the local projection (low-latency, resilient to transfers downtime); only account reads —
  which have no local projection — are proxied.

## Consequences

- One money-movement primitive (the balanced posting); opening deposits are ordinary, auditable ledger
  history. The `bank-equity` account's negative balance is the bank's funding position.
- Balances/statements are always consistent with the ledger by construction; no reconciliation job.
  Read cost scales with entry count per account (acceptable here; snapshot optimization left open).
- Frozen/closed accounts are rejected as posting endpoints for free (BANK-6 guard on `isOperational()`).
- The gateway is the single public edge for accounts too; its contract is the source of truth and the
  served copy can never silently drift (byte-equality + operationId drift tests).
- Account opening is safely retryable (request-id idempotency anchor), like transfer posting.
