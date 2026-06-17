---
status: accepted
date: 2026-06-17
---

# Utility starters: cache (Caffeine), resilience (Resilience4j), feature flags (OpenFeature)

## Decision Outcome

- `acme-cache`: Spring Cache backed by Caffeine with sane defaults (10-min TTL, 10k entries),
  overridable `CacheManager`. Redis L2 / true two-tier with cross-instance invalidation deferred.
- `acme-cache` two-tier (opt-in, `acme.cache.two-tier.enabled=true` + Redis on classpath): Caffeine
  L1 in front of Redis L2 (`TwoTierCacheManager`/`TwoTierCache`) — reads populate L1 from L2, writes/
  evicts hit both. Cross-instance L1 invalidation via Redis pub/sub is a future enhancement.
- `acme-resilience`: Resilience4j Spring Boot 3 (2.4.0) so `@Retry`/`@CircuitBreaker`/`@Bulkhead`
  annotations work; instances configured via `resilience4j.*` properties.
- `acme-featureflags`: OpenFeature SDK (1.20.2) `Client` over an overridable `FeatureProvider`
  (default `NoOpProvider`); a consumer supplies an in-memory/flagd/vendor provider bean.
- Each is verified in-process (Caffeine caching hit-count, Resilience4j retry recovery, OpenFeature
  flag evaluation) — no containers required.

Full detail: `docs/superpowers/specs/2026-06-17-acme-boilerplate-design.md` §5.
