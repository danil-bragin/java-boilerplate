# acme-test-support

Shared Testcontainers configuration classes for integration tests. Each exposes a real backing
service (Postgres, Redpanda, Redis) as importable `@TestConfiguration` beans that wire themselves
into the Spring Boot test context, so a test gets a live dependency with zero datasource setup.

## What it provides
- `PostgresTestcontainersConfiguration` — `@TestConfiguration(proxyBeanMethods = false)` exposing a
  `PostgreSQLContainer<?>` on image `postgres:16-alpine` (2-minute startup timeout for slow CI). A
  `DynamicPropertyRegistrar` bean publishes `spring.datasource.url` / `username` / `password`.
- `RedpandaTestcontainersConfiguration` — `@TestConfiguration(proxyBeanMethods = false)` exposing a
  `RedpandaContainer` (Kafka-compatible) on image `redpandadata/redpanda:v24.2.7` with a 3-minute
  startup timeout. Wired to Spring Boot via `@ServiceConnection` (no manual property registration).
- `RedisTestcontainersConfiguration` — `@TestConfiguration(proxyBeanMethods = false)` exposing a
  `GenericContainer<?>` on image `redis:7-alpine` (port 6379). A `DynamicPropertyRegistrar` bean
  publishes `spring.data.redis.host` / `spring.data.redis.port`.

The module is `api`-scoped onto Spring Boot Testcontainers, the Postgres/Redpanda Testcontainers
modules, JUnit Jupiter, Spring Kafka, and Spring Data Redis, so importers inherit them transitively.

## Usage
```kotlin
testImplementation("acme-bank:acme-test-support")
```
In an integration test, `@Import` the configuration(s) you need (e.g.
`@Import(PostgresTestcontainersConfiguration.class)`) onto a `@SpringBootTest`; the container starts
and its connection properties are bound automatically.

## See also
- root README
