---
status: accepted
date: 2026-06-18
---

# Audit attribution: who created/modified an entity

## Decision Outcome

- `AuditedEntity` gains `@CreatedBy`/`@LastModifiedBy` (`created_by`/`last_modified_by`, nullable),
  populated by `SecurityAuditorAware` (from `acme-security`) which reads the authenticated principal
  from the Spring `SecurityContext`. Unauthenticated saves leave the columns null.
- This is the lightweight "who" audit. Heavier options remain available but out of scope here:
  Hibernate Envers (full revision history tables) and a tamper-evident hash-chained audit log.
- Verified (`AuditAttributionIT`): an order saved under principal `alice` records `created_by=alice`;
  an unauthenticated save records null.

Full detail: `docs/superpowers/specs/2026-06-17-acme-boilerplate-design.md` §5 (acme-security/audit).
