---
status: accepted
date: 2026-06-17
---

# Persistence: Spring Data JPA, Oracle-first but DB-agnostic

## Context and Problem Statement

The boilerplate needs a reusable persistence layer. The target infrastructure is Oracle, but
the reusable layer must not be coupled to a specific database, and local/CI verification needs a
fast, reliable container (the Oracle Free image is large and not always reachable).

## Decision Outcome

- Spring Data JPA / Hibernate via the `acme-persistence` starter; `AuditedEntity` provides
  clock-backed `@CreatedDate`/`@LastModifiedDate` and an optimistic-locking `@Version`.
- IDs use `GenerationType.SEQUENCE` (portable; not `IDENTITY`). Identifiers kept short for Oracle.
- Flyway owns all DDL via `classpath:db/migration/{vendor}`; Oracle is the reference vendor dir,
  Postgres the swappable path.
- Integration tests run on Postgres Testcontainers (`postgres:16-alpine`, locally cached).
  Oracle verification is a deferred opt-in profile — the Oracle Free Testcontainers image is not
  pulled in this environment.
- Switching the runtime DB = profile + JDBC driver + Hibernate dialect; no code changes.

Full detail: `docs/superpowers/specs/2026-06-17-acme-boilerplate-design.md` §5 (acme-persistence).
