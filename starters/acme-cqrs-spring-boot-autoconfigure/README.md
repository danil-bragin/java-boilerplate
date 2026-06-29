# acme-cqrs-spring-boot-autoconfigure

Auto-configures a [PipelinR](https://github.com/sizovs/pipelinr) command/query bus assembled from
Spring beans. Handlers and middleware are collected from the context and composed into an explicit
pipeline — no Spring AOP, so no proxy/self-invocation traps. Uses jMolecules-style marker interfaces
to express a command's consistency intent.

## What it configures

`CqrsAutoConfiguration` (`@AutoConfiguration(after = {TransactionAutoConfiguration, HibernateJpaAutoConfiguration})`,
gated `@ConditionalOnClass(an.awesome.pipelinr.Pipeline)`):

- **`Pipeline` bean** (`@ConditionalOnMissingBean`) — a `Pipelinr` wired from every
  `Command.Handler` bean and every `Command.Middleware` bean, both pulled via `ObjectProvider.orderedStream()`
  so `@Order` decides middleware nesting.
- **`ValidationMiddleware`** (`@Order(10)`, gated `@ConditionalOnClass(jakarta.validation.Validator)` +
  `@ConditionalOnMissingBean`) — runs Jakarta Bean Validation on each command before its handler;
  throws `ConstraintViolationException` on violations. Outermost behavior.
- **`TransactionMiddleware`** (`@Order(20)`, gated `@ConditionalOnBean(PlatformTransactionManager)` +
  `@ConditionalOnMissingBean`) — wraps commands implementing the `StronglyConsistent` marker in a
  programmatic `TransactionTemplate`; unmarked commands run with no transaction.

Marker interfaces (in `com.acme.cqrs`):

- **`StronglyConsistent`** — opt-in marker that makes `TransactionMiddleware` open a transaction.
- **`EventuallyConsistent`** — documentation/seam marker; treated exactly like an unmarked command
  (no transaction). Signals correctness comes from the outbox/inbox + reconciliation instead.

## Usage

```kotlin
implementation("acme-bank:acme-cqrs-spring-boot-starter")
```

With PipelinR on the classpath the `Pipeline` bean and middleware auto-activate; inject `Pipeline`
and call `command.execute(pipeline)`.

## See also

- ADR-0004 — CQRS: lightweight command bus via PipelinR + explicit middleware
- Root README
