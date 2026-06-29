# acme-persistence

Base JPA persistence wiring for ACME services. Activates Spring Data JPA auditing backed by an
injectable `Clock` so "now" is deterministic in tests, and ships reusable mapped-superclass and
embeddable types for audited entities and money columns.

## What it configures
`PersistenceAutoConfiguration` (`@ConditionalOnClass(AuditingEntityListener.class)`,
`@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")`):
- `clock()` — `Clock.systemUTC()` bean, `@ConditionalOnMissingBean` (overridable).
- `auditingDateTimeProvider` — `DateTimeProvider` returning `clock.instant()`, `@ConditionalOnMissingBean(name=...)`. Drives `@CreatedDate` / `@LastModifiedDate`.

Public types (use directly in your entities):
- `AuditedEntity` — `@MappedSuperclass` with `@Version` optimistic-lock column plus `created_at`/`updated_at` (`@CreatedDate`/`@LastModifiedDate`) and `created_by`/`last_modified_by` (`@CreatedBy`/`@LastModifiedBy`) attribution columns. Populating the *_by columns requires your own `AuditorAware` bean.
- `MoneyAmount` — `@Embeddable` mapping a `com.acme.money.Money` to two columns: `amount` (`NUMERIC`, precision 38 scale 18) + `asset` (`VARCHAR(16)`). `MoneyAmount.from(Money)` / `toMoney(AssetLookup)`.
- `AssetLookup` — `@FunctionalInterface` resolving an asset code to a `com.acme.money.Asset`.

No configurable properties.

## Usage
```kotlin
implementation("acme-bank:acme-persistence-spring-boot-starter")
```
On the classpath (with `AuditingEntityListener` present), JPA auditing and the clock-backed
`DateTimeProvider` activate automatically. The starter adds Flyway plus the Postgres and Oracle
JDBC drivers as runtime deps.

## See also
- ADR-0002 Persistence: Spring Data JPA, Oracle-first but DB-agnostic
- ADR-0010 Audit attribution: who created/modified an entity
- root README
