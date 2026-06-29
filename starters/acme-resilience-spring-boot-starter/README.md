# acme-resilience

Standalone starter (no autoconfigure sibling). Aggregates Resilience4j's Spring Boot 3 integration (`io.github.resilience4j:resilience4j-spring-boot3` 2.4.0) and Spring Boot's AOP starter so fault-tolerance annotations work out of the box. It brings the dependencies and AOP plumbing; you declare the resilience behavior via configuration and annotations.

## What it configures
- Pulls `resilience4j-spring-boot3`, whose own auto-configuration registers the Resilience4j registries and the aspects backing the annotations.
- Pulls `spring-boot-starter-aop` (AspectJ proxying), required for Resilience4j's annotation aspects to intercept method calls.
- Enables the Resilience4j annotations: `@Retry`, `@CircuitBreaker`, `@TimeLimiter`, `@Bulkhead` (and `@RateLimiter`), configured per-instance under `resilience4j.*` in application config.
- Resilience4j auto-config also exposes its registries to Micrometer when a `MeterRegistry` is present, surfacing circuit-breaker/retry metrics.

## Key properties
Configured under Resilience4j's own namespaces, e.g. `resilience4j.retry.instances.<name>.*`, `resilience4j.circuitbreaker.instances.<name>.*`, `resilience4j.timelimiter.instances.<name>.*`, `resilience4j.bulkhead.instances.<name>.*`. See the Resilience4j Spring Boot 3 reference for the full field list. This starter ships no `acme.*` properties.

## Usage
```kotlin
implementation("acme-bank:acme-resilience-spring-boot-starter")
```
On the classpath this enables Resilience4j's annotation aspects; annotate methods (e.g. `@CircuitBreaker(name = "...")`) and tune instances under `resilience4j.*`.

## See also
- ADR-0007 Utility starters: cache (Caffeine), resilience (Resilience4j), feature flags (OpenFeature)
- root README
