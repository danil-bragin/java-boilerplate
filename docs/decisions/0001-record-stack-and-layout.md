---
status: accepted
date: 2026-06-17
---

# Monorepo layout, custom starters, and core stack

## Context and Problem Statement

This is a Spring Boot microservice boilerplate (analogue of go-boilerplate) built on
ready-made Spring solutions. We need to fix the build layout, packaging mechanism, and stack.

## Decision Outcome

- Gradle (Kotlin DSL) monorepo: `build-logic` convention plugins, `java-platform` `acme-bom`,
  `gradle/libs.versions.toml` catalog.
- Reusable cross-cutting concerns ship as `acme-*-spring-boot-autoconfigure` + thin
  `acme-*-spring-boot-starter` module pairs.
- Java 21, Spring Boot 3.5.x. Oracle Database = primary/reference, Postgres swappable;
  reusable layer stays DB-agnostic.
- Full rationale and per-starter detail: `docs/superpowers/specs/2026-06-17-acme-boilerplate-design.md`.
