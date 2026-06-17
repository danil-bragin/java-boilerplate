---
status: accepted
date: 2026-06-17
---

# CQRS: lightweight command bus via PipelinR + explicit middleware

## Context and Problem Statement

We want the spirit of a typed command/handler pipeline with cross-cutting behaviors, without a
heavy framework (Axon) and without Spring AOP's self-invocation pitfalls.

## Decision Outcome

- PipelinR (`net.sizovs:pipelinr` 0.11, package `an.awesome.pipelinr`) provides the typed
  `Command<R>` / `Command.Handler<C,R>` bus. The `acme-cqrs` starter auto-wires a `Pipeline` from
  all handler beans + an ordered middleware list.
- Cross-cutting concerns are explicit middleware (not AOP): `ValidationMiddleware` (Jakarta Bean
  Validation, outermost, @Order 10) then `TransactionMiddleware` (@Order 20).
- Consistency is opt-in per command: only commands implementing `StronglyConsistent` run inside a
  programmatic `TransactionTemplate`. Strong consistency is the default intent; relaxation
  (no marker -> no transaction) is explicit, per spec §7.
- `CqrsAutoConfiguration` is ordered `@AutoConfiguration(after = {TransactionAutoConfiguration,
  HibernateJpaAutoConfiguration})` so its `@ConditionalOnBean(PlatformTransactionManager)` sees
  the JPA transaction manager (otherwise the transaction middleware is silently skipped).
- Verified end-to-end: a `StronglyConsistent` command that saves then throws rolls back.

Full detail: `docs/superpowers/specs/2026-06-17-acme-boilerplate-design.md` §5 (acme-cqrs).
