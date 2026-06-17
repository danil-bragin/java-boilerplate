# SP-5: Security Starter (OAuth2 Resource Server + Keycloak) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** Add a reusable `acme-security` starter that turns a service into a stateless OAuth2 JWT resource server (Keycloak-compatible) with Keycloak realm-role → Spring authority mapping and method-level RBAC, and prove it in `demo-service` (401 unauthenticated, 403 missing role, 200 with role) using mock JWTs — no Keycloak container required.

**Architecture:** The autoconfigure module ships a `KeycloakJwtAuthenticationConverter` (maps `realm_access.roles` → `ROLE_*` plus standard scopes) and a default `SecurityFilterChain` (`@ConditionalOnMissingBean`) that permits a configurable path list (actuator health/info by default), requires authentication everywhere else, wires `oauth2ResourceServer().jwt()` with the converter, is stateless, and enables method security (`@EnableMethodSecurity`). The runtime JWT validation key comes from `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` (Keycloak's JWKS endpoint; a lazy decoder, so startup needs no network). Tests authenticate with `spring-security-test`'s `jwt()` request post-processor, so they exercise the filter chain + `@PreAuthorize` without a real IdP; the converter's claim-mapping logic is unit-tested directly.

**Tech Stack:** Java 21, Spring Boot 3.5.6, Spring Security 6.5 (OAuth2 Resource Server, method security), spring-security-test, JUnit 5 + AssertJ.

> Spec: `docs/superpowers/specs/2026-06-17-acme-boilerplate-design.md` §5 (acme-security). Builds on SP-0..4.
> **Environment (every task):** work from `/Users/npden4ik/Projects/java-boilerplate`; system `gradle` (8.14) NEVER `./gradlew`; JDK 21 toolchain; Maven Central fast; Docker up (Postgres + Redpanda cached — the demo's other ITs still run). `gradle <module>:spotlessApply` before each commit.
> **Keycloak note:** Keycloak is the runtime identity provider (configured via `jwk-set-uri`/`issuer-uri`), but tests use mock JWTs — no Keycloak image is pulled.

---

## File structure

```
gradle/libs.versions.toml                          MODIFY: oauth2 resource server + security-test aliases
starters/acme-security-spring-boot-autoconfigure/
  build.gradle.kts                                 NEW
  src/main/java/com/acme/security/KeycloakJwtAuthenticationConverter.java   NEW
  src/main/java/com/acme/security/SecurityProperties.java                   NEW
  src/main/java/com/acme/security/autoconfigure/SecurityAutoConfiguration.java  NEW
  src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports  NEW
  src/test/java/com/acme/security/KeycloakJwtAuthenticationConverterTest.java   NEW
starters/acme-security-spring-boot-starter/build.gradle.kts                NEW
examples/demo-service/
  build.gradle.kts                                 MODIFY: add security starter + security-test
  src/main/java/com/acme/demo/AdminController.java                NEW (@PreAuthorize)
  src/main/resources/application.yaml              MODIFY: jwk-set-uri + permit paths
  src/test/java/com/acme/demo/OrderControllerIT.java   MODIFY: authenticate requests
  src/test/java/com/acme/demo/SecurityIT.java          NEW
settings.gradle.kts                                MODIFY: include security modules
docs/decisions/0006-security-oauth2-keycloak.md    NEW
```

---

## Task 1: Version catalog

Modify `gradle/libs.versions.toml` (keep existing). Add to `[libraries]`:
```toml
spring-boot-starter-security = { module = "org.springframework.boot:spring-boot-starter-security" }
spring-boot-starter-oauth2-resource-server = { module = "org.springframework.boot:spring-boot-starter-oauth2-resource-server" }
spring-security-test = { module = "org.springframework.security:spring-security-test" }
```
- [ ] **Step 1:** Apply.
- [ ] **Step 2:** Verify `gradle :platform:acme-bom:help -q` → BUILD SUCCESSFUL.
- [ ] **Step 3:** Commit:
```bash
git add gradle/libs.versions.toml
git commit -m "build: add oauth2 resource server + security-test aliases"
```

---

## Task 2: acme-security autoconfigure — converter (TDD), properties, filter chain

**Files:** settings.gradle.kts (modify), module build.gradle.kts, `KeycloakJwtAuthenticationConverter.java`, `SecurityProperties.java`, `SecurityAutoConfiguration.java`, `AutoConfiguration.imports`, `KeycloakJwtAuthenticationConverterTest.java`.

- [ ] **Step 1: settings** — add the two security modules to `include(...)` (after the outbox starter entry):
```kotlin
    "starters:acme-security-spring-boot-autoconfigure",
    "starters:acme-security-spring-boot-starter",
```

- [ ] **Step 2: dirs**
```bash
mkdir -p starters/acme-security-spring-boot-autoconfigure/src/main/java/com/acme/security/autoconfigure \
  starters/acme-security-spring-boot-autoconfigure/src/main/resources/META-INF/spring \
  starters/acme-security-spring-boot-autoconfigure/src/test/java/com/acme/security \
  starters/acme-security-spring-boot-starter
```

- [ ] **Step 3: module build script** — `starters/acme-security-spring-boot-autoconfigure/build.gradle.kts`:
```kotlin
plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(libs.spring.boot.autoconfigure)
    api(libs.spring.boot.starter.oauth2.resource.server)

    annotationProcessor(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)

    testImplementation(libs.spring.boot.starter.test)
}
```

- [ ] **Step 4: failing converter test** — `.../src/test/java/com/acme/security/KeycloakJwtAuthenticationConverterTest.java`:
```java
package com.acme.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakJwtAuthenticationConverterTest {

    private final KeycloakJwtAuthenticationConverter converter = new KeycloakJwtAuthenticationConverter();

    @Test
    void mapsRealmRolesToPrefixedAuthorities() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuedAt(Instant.EPOCH)
                .expiresAt(Instant.EPOCH.plusSeconds(60))
                .subject("user-1")
                .claim("realm_access", Map.of("roles", List.of("ADMIN", "USER")))
                .build();

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities())
                .extracting("authority")
                .contains("ROLE_ADMIN", "ROLE_USER");
    }

    @Test
    void handlesMissingRealmAccessClaim() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuedAt(Instant.EPOCH)
                .expiresAt(Instant.EPOCH.plusSeconds(60))
                .subject("user-2")
                .build();

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities()).isEmpty();
    }
}
```

- [ ] **Step 5: run, verify FAIL** — `gradle :starters:acme-security-spring-boot-autoconfigure:test --tests "*KeycloakJwtAuthenticationConverterTest"` → FAIL (converter missing).

- [ ] **Step 6: converter** — `.../src/main/java/com/acme/security/KeycloakJwtAuthenticationConverter.java`:
```java
package com.acme.security;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Maps a Keycloak JWT to Spring authorities: standard scopes (via {@link JwtGrantedAuthoritiesConverter})
 * plus realm roles from the {@code realm_access.roles} claim, each prefixed {@code ROLE_}.
 */
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new HashSet<>(scopes.convert(jwt));
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess != null && realmAccess.get("roles") instanceof Collection<?> roles) {
            for (Object role : roles) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
        }
        return new JwtAuthenticationToken(jwt, authorities);
    }
}
```

- [ ] **Step 7: properties** — `.../src/main/java/com/acme/security/SecurityProperties.java`:
```java
package com.acme.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the security starter. */
@ConfigurationProperties(prefix = "acme.security")
public class SecurityProperties {

    /** Ant patterns permitted without authentication. Defaults to actuator health + info. */
    private List<String> permitPaths = List.of("/actuator/health/**", "/actuator/info");

    public List<String> getPermitPaths() {
        return permitPaths;
    }

    public void setPermitPaths(List<String> permitPaths) {
        this.permitPaths = permitPaths;
    }
}
```

- [ ] **Step 8: auto-configuration** — `.../src/main/java/com/acme/security/autoconfigure/SecurityAutoConfiguration.java`:
```java
package com.acme.security.autoconfigure;

import com.acme.security.KeycloakJwtAuthenticationConverter;
import com.acme.security.SecurityProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Configures a stateless OAuth2 JWT resource server with Keycloak realm-role mapping and method
 * security. Permits a configurable path list; everything else requires authentication. Fully
 * overridable — a consumer can define their own {@link SecurityFilterChain}.
 */
@AutoConfiguration
@ConditionalOnClass(SecurityFilterChain.class)
@EnableConfigurationProperties(SecurityProperties.class)
@EnableMethodSecurity
public class SecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public KeycloakJwtAuthenticationConverter keycloakJwtAuthenticationConverter() {
        return new KeycloakJwtAuthenticationConverter();
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityFilterChain acmeSecurityFilterChain(
            HttpSecurity http, KeycloakJwtAuthenticationConverter converter, SecurityProperties props)
            throws Exception {
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers(props.getPermitPaths().toArray(String[]::new))
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
```

- [ ] **Step 9: registration** — `.../resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
```
com.acme.security.autoconfigure.SecurityAutoConfiguration
```

- [ ] **Step 10: run, verify PASS** — `gradle :starters:acme-security-spring-boot-autoconfigure:test --tests "*KeycloakJwtAuthenticationConverterTest"` → PASS; `:compileJava` → BUILD SUCCESSFUL.

- [ ] **Step 11: format + commit**
```bash
gradle :starters:acme-security-spring-boot-autoconfigure:spotlessApply
git add settings.gradle.kts starters/acme-security-spring-boot-autoconfigure
git commit -m "feat(acme-security): OAuth2 JWT resource server + Keycloak role mapping + method security"
```

---

## Task 3: acme-security thin starter

Create `starters/acme-security-spring-boot-starter/build.gradle.kts`:
```kotlin
plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(project(":starters:acme-security-spring-boot-autoconfigure"))
    api(libs.spring.boot.starter.security)
    api(libs.spring.boot.starter.oauth2.resource.server)
}
```
- [ ] **Step 1:** Create.
- [ ] **Step 2:** Verify `gradle :starters:acme-security-spring-boot-starter:assemble` → BUILD SUCCESSFUL.
- [ ] **Step 3:** Commit:
```bash
git add starters/acme-security-spring-boot-starter/build.gradle.kts
git commit -m "feat(acme-security): thin starter (spring-security + oauth2 resource server)"
```

---

## Task 4: demo — secure the service, add an RBAC endpoint, fix existing IT

**Files:** demo build.gradle.kts (modify), `AdminController.java`, application.yaml (modify), `OrderControllerIT.java` (modify).

- [ ] **Step 1: deps** — in `examples/demo-service/build.gradle.kts`: add to `dependencies { }` after the outbox starter:
```kotlin
    implementation(project(":starters:acme-security-spring-boot-starter"))
```
and add a test dep:
```kotlin
    testImplementation(libs.spring.security.test)
```

- [ ] **Step 2: RBAC endpoint** — `examples/demo-service/src/main/java/com/acme/demo/AdminController.java`:
```java
package com.acme.demo;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Demonstrates method-level RBAC: only callers with the ADMIN realm role may access. */
@RestController
@RequestMapping("/v1/admin")
public class AdminController {

    @GetMapping("/ping")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> ping() {
        return Map.of("pong", true);
    }
}
```

- [ ] **Step 3: config** — in `examples/demo-service/src/main/resources/application.yaml`, add under the existing `spring:` mapping a `security` block (keep all existing keys):
```yaml
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: ${KEYCLOAK_JWKS:http://localhost:8081/realms/demo/protocol/openid-connect/certs}
```
> `jwk-set-uri` builds a lazy `NimbusJwtDecoder` (no network call at startup). In a real deployment set `KEYCLOAK_JWKS` to the realm's JWKS endpoint. Tests bypass the decoder via mock JWTs.

- [ ] **Step 4: fix the existing web IT to authenticate.** Replace the ENTIRE contents of `examples/demo-service/src/test/java/com/acme/demo/OrderControllerIT.java`:
```java
package com.acme.demo;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.test.PostgresTestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfiguration.class)
class OrderControllerIT {

    @Autowired
    MockMvc mvc;

    @Test
    void returnsProblemJsonForMissingOrder() throws Exception {
        mvc.perform(get("/v1/orders/999999").with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.title").value("Order not found"))
                .andExpect(jsonPath("$.params.orderId").value(999999));
    }

    @Test
    void returnsValidationProblemForBadBody() throws Exception {
        mvc.perform(post("/v1/orders")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"\",\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors").isArray());
    }
}
```
> Only this HTTP IT needs authentication. `ActuatorHealthIT` hits permitted health endpoints; `OrderPersistenceIT`, `CreateOrderCommandIT`, `OutboxExternalizationIT`, `SchedulerLockIT` use the bus/repo/JdbcTemplate directly (no HTTP), so they are unaffected by the HTTP security filter.

- [ ] **Step 5: compile + run the affected suites** — `gradle :examples:demo-service:test --tests "*OrderControllerIT" --tests "*ActuatorHealthIT"` → PASS.

- [ ] **Step 6: commit**
```bash
gradle :examples:demo-service:spotlessApply
git add examples/demo-service
git commit -m "feat(demo): secure endpoints via OAuth2 resource server + ADMIN-only admin endpoint"
```

---

## Task 5: demo — security integration test (401 / 403 / 200)

Create `examples/demo-service/src/test/java/com/acme/demo/SecurityIT.java`:
```java
package com.acme.demo;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.test.PostgresTestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfiguration.class)
class SecurityIT {

    @Autowired
    MockMvc mvc;

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mvc.perform(get("/v1/orders/1")).andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedWithoutAdminRoleIsForbidden() throws Exception {
        mvc.perform(get("/v1/admin/ping").with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void authenticatedWithAdminRoleIsAllowed() throws Exception {
        mvc.perform(get("/v1/admin/ping")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void healthProbeIsPublic() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }
}
```
> Proof: the filter chain returns 401 without a token; `@PreAuthorize("hasRole('ADMIN')")` returns 403 for an authenticated caller lacking the role and 200 when the `ROLE_ADMIN` authority is present; the permitted health path stays public. (The `jwt()` post-processor sets the authentication directly, so this exercises the filter chain + method security; the realm-role → `ROLE_` mapping itself is unit-tested in `KeycloakJwtAuthenticationConverterTest`.)

- [ ] **Step 2: run** — `gradle :examples:demo-service:test --tests "*SecurityIT"` → PASS (4 tests).
- [ ] **Step 3: full demo suite** — `gradle :examples:demo-service:test` → all green (no regressions).
- [ ] **Step 4: commit**
```bash
gradle :examples:demo-service:spotlessApply
git add examples/demo-service/src/test
git commit -m "test(demo): security matrix — 401 unauthenticated, 403 missing role, 200 with role"
```

---

## Task 6: Full build + ADR

- [ ] **Step 1:** `gradle build` → BUILD SUCCESSFUL (all modules, Spotless, all tests). Fix Spotless via `spotlessApply`; debug real failures; BLOCKED if stuck.
- [ ] **Step 2:** Create `docs/decisions/0006-security-oauth2-keycloak.md`:
```markdown
---
status: accepted
date: 2026-06-17
---

# Security: OAuth2 JWT resource server (Keycloak) + method RBAC

## Context and Problem Statement

Services must authenticate callers and enforce role-based access, integrating with Keycloak,
while staying testable without standing up an identity provider.

## Decision Outcome

- The `acme-security` starter makes a service a stateless OAuth2 **resource server**: it validates
  JWTs against Keycloak's JWKS (`spring.security.oauth2.resourceserver.jwt.jwk-set-uri`, a lazy
  decoder — no startup network call).
- `KeycloakJwtAuthenticationConverter` maps `realm_access.roles` to `ROLE_*` authorities (plus
  standard scopes), so `@PreAuthorize("hasRole('...')")` works against Keycloak realm roles.
- A default, overridable `SecurityFilterChain` permits a configurable path list (actuator
  health/info by default) and authenticates everything else; method security is enabled.
- Tests use `spring-security-test`'s `jwt()` post-processor (mock JWTs) to verify the 401/403/200
  matrix without a Keycloak container; the converter's claim mapping is unit-tested directly.

Full detail: `docs/superpowers/specs/2026-06-17-acme-boilerplate-design.md` §5 (acme-security).
```
- [ ] **Step 3:** Commit:
```bash
git add docs/decisions/0006-security-oauth2-keycloak.md
git commit -m "docs: ADR 0006 security OAuth2 resource server + Keycloak RBAC"
```

---

## Done criteria for SP-5

- `gradle build` green (all modules, Spotless, all tests).
- `acme-security` starter: stateless JWT resource server + Keycloak realm-role mapping + method security; overridable filter chain.
- demo is secured; ITs prove 401 (unauthenticated), 403 (missing role), 200 (with role), and public health.
- Existing demo ITs updated to authenticate; no regressions.

---

## Self-review notes

- **Spec coverage (§5 acme-security):** OAuth2 resource server + Keycloak ✓ (jwk-set-uri), realm-role → authority mapping ✓ (converter), `@EnableMethodSecurity` + `@PreAuthorize` RBAC ✓, overridable filter chain ✓, permit-list config ✓. Multi-tenancy: explicitly OUT of scope (removed from the design, §10a). Audit (Envers/hash-chain): deferred to a later SP. Principal-over-Kafka: deferred (SP-4b consumer work).
- **Type consistency:** `KeycloakJwtAuthenticationConverter.convert(Jwt) : AbstractAuthenticationToken` used in unit test + filter chain; `SecurityProperties.getPermitPaths()` used in the filter chain; `ROLE_ADMIN` authority in `SecurityIT` matches `@PreAuthorize("hasRole('ADMIN')")` (hasRole adds the ROLE_ prefix).
- **No placeholders.** Concrete throughout.
- **Test realism:** the converter mapping (realm_access → ROLE_) is unit-tested; the security enforcement (401/403/200/public) is integration-tested via the real filter chain with mock JWTs — no Keycloak image needed, fully reliable in CI.
- **Blast radius:** introducing security requires only `OrderControllerIT` to authenticate (the sole HTTP MockMvc IT besides the health IT, which hits permitted paths). Non-HTTP ITs are unaffected.
