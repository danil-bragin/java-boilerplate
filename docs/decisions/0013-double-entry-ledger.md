---
status: accepted
date: 2026-06-18
---

# Double-entry ledger (acme-bank accounts)

## Decision Outcome

- The `accounts` service is the protected hexagonal+DDD core. `Posting` is an immutable, balanced
  double-entry transaction: signed `Money` entries summing to zero within one asset (invariant in the
  domain constructor, jMolecules `@AggregateRoot`). Append-only; corrections are new postings.
- Balance is purely derived (`SUM(entries)` per account+asset) — no materialized balance, no view.
- `PostTransfer` is an `acme-cqrs` `StronglyConsistent` command: the idempotency check (by transfer id),
  source-balance fund check, and posting save commit in one DB transaction. Insufficient funds → no
  entries written; a retried transfer id applies once.
- `Money` persists via the reusable `acme-persistence` `MoneyAmount` `@Embeddable` (`NUMERIC(38,18)`
  amount + `VARCHAR` asset) — exact, lossless, vendor-portable (Postgres tested, Oracle reference).
- ArchUnit fitness functions enforce hexagonal boundaries: the domain depends on no Spring/JPA/adapter.

Full design: `docs/superpowers/specs/2026-06-18-acme-bank-design.md` §6.
