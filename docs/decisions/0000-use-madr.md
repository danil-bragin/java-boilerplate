---
status: accepted
date: 2026-06-17
---

# Use Markdown Any Decision Records (MADR)

## Context and Problem Statement

We need a lightweight, in-repo way to capture architectural decisions and their rationale.

## Decision Outcome

Adopt MADR 4.0.0. ADRs live in `docs/decisions/` as `NNNN-kebab-title.md` with a `status:`
front-matter field (`proposed | accepted | rejected | deprecated | superseded by NNNN`).
ADRs are immutable — supersede, never rewrite.
