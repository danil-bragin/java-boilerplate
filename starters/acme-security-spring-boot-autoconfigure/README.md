# acme-security-spring-boot-autoconfigure

Auto-configures a stateless OAuth2 JWT resource server tuned for Keycloak. Maps Keycloak realm roles to Spring authorities, enables method-level RBAC, and supplies an `AuditorAware` so JPA auditing records the authenticated principal. Every bean is `@ConditionalOnMissingBean`, so consumers can override any piece.

## What it configures
- `SecurityFilterChain acmeSecurityFilterChain` — permits `acme.security.permit-paths`, requires authentication for everything else; OAuth2 resource server with JWT, `SessionCreationPolicy.STATELESS`, CSRF disabled. Gated `@ConditionalOnClass(SecurityFilterChain.class)`.
- `KeycloakJwtAuthenticationConverter` — combines standard scope authorities (`JwtGrantedAuthoritiesConverter`) with realm roles from the `realm_access.roles` claim, each prefixed `ROLE_`.
- `@EnableMethodSecurity` — activates `@PreAuthorize`/`@PostAuthorize` RBAC on the application context.
- `SecurityAuditorAware` — `AuditorAware<String>` returning the current principal name (empty for anonymous/unauthenticated) for JPA `@CreatedBy`/`@LastModifiedBy`.

## Key properties
| Property | Default | Purpose |
|---|---|---|
| `acme.security.permit-paths` | `/actuator/health/**`, `/actuator/info` | Ant patterns permitted without authentication |

## Usage
```kotlin
implementation("acme-bank:acme-security-spring-boot-starter")
```
On the classpath this activates `SecurityAutoConfiguration`; supply `spring.security.oauth2.resourceserver.jwt.*` (e.g. issuer URI) to point at your Keycloak realm.

## See also
- ADR-0006 Security: OAuth2 JWT resource server (Keycloak) + method RBAC
- ADR-0010 Audit attribution: who created/modified an entity
- root README
