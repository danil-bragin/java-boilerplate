---
status: accepted
date: 2026-06-17
---

# Security: OAuth2 JWT resource server (Keycloak) + method RBAC

## Context and Problem Statement

Services must authenticate callers and enforce role-based access, integrating with Keycloak,
while staying testable without standing up an identity provider.

## Decision Outcome

- The `acme-security` starter makes a service a stateless OAuth2 resource server: it validates
  JWTs against Keycloak's JWKS (`spring.security.oauth2.resourceserver.jwt.jwk-set-uri`, a lazy
  decoder — no startup network call).
- `KeycloakJwtAuthenticationConverter` maps `realm_access.roles` to `ROLE_*` authorities (plus
  standard scopes), so `@PreAuthorize("hasRole('...')")` works against Keycloak realm roles.
- A default, overridable `SecurityFilterChain` permits a configurable path list (actuator
  health/info by default) and authenticates everything else; method security is enabled.
- **Secure-by-default note:** only `/actuator/health/**` and `/actuator/info` are public; everything
  else — including `/actuator/prometheus` — requires a JWT. If your Prometheus scraper cannot
  authenticate, either scrape over a separate management port/network policy or add
  `/actuator/prometheus` to `acme.security.permit-paths`.
- Tests use spring-security-test's `jwt()` post-processor (mock JWTs) for the 401/403/200 matrix; the
  converter's claim mapping is unit-tested directly AND exercised end-to-end through the real filter
  chain via a stub `JwtDecoder` (`SecurityConverterIT`), proving it is wired into the resource server.

Full detail: `docs/superpowers/specs/2026-06-17-acme-boilerplate-design.md` §5 (acme-security).
