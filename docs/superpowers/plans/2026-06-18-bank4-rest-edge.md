# BANK-4: REST edge (transfers web adapter) + acme-web idempotency & rate-limit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Give the `transfers` service a secured REST edge (the "gateway" role — it owns the `Transfer` aggregate + status): `POST /v1/transfers` initiates a transfer (OAuth2 JWT, `Idempotency-Key`, RFC 9457 problem+json, rate-limited) and `GET /v1/transfers/{id}` returns its status. Realize the two deferred `acme-web` improvements: an **idempotency filter** (replay the response for a repeated `Idempotency-Key`) and **rate limiting** (Bucket4j).

**Architecture:** The `acme-web` autoconfigure gains an `IdempotencyFilter` (`OncePerRequestFilter`) backed by an overridable `IdempotencyStore` (in-memory default) that caches a completed response per `Idempotency-Key` and replays it on repeat; the `acme-web` starter adds the Bucket4j Spring Boot starter so a service enables rate limiting purely via `bucket4j.*` properties. `transfers` adds an `adapter/in/web` (`TransferController`, DTOs) calling the existing `InitiateTransfer` command via the `Pipeline`, secured by `acme-security`, with errors rendered by `acme-web`'s problem+json. The gateway-as-separate-service from the diagram is collapsed into the transfers web adapter (it already owns the saga + status); a note documents that a dedicated BFF/gateway would front this in a larger org.

**Tech Stack:** Java 21, Spring Boot 3.5.6, acme-web/-security/-cqrs/-persistence starters, Bucket4j 0.13.0, Spring Security test (mock JWT), Testcontainers Postgres, MockMvc.

> Spec: `docs/superpowers/specs/2026-06-18-acme-bank-design.md` §5 (gateway/acme-web), §9. Builds on BANK-0..3.
> **Reference patterns (read in-repo):** `starters/acme-web-spring-boot-autoconfigure` (the `ProblemExceptionHandler` + `AcmeWebAutoConfiguration` + `AutoConfiguration.imports`), `examples/demo-service` (`OrderControllerIT` security with `jwt()`, `SecurityIT`), `examples/acme-bank/transfers` (the `InitiateTransferCommand` + `Pipeline`).
> **Environment (every task):** work from `/Users/npden4ik/Projects/java-boilerplate`; system `gradle` (8.14) NEVER `./gradlew`; JDK 21 toolchain; Docker up, Postgres cached. `gradle <module>:spotlessApply` before each commit.

---

## Task 1: catalog — Bucket4j alias

- [ ] **Step 1:** In `gradle/libs.versions.toml` add to `[versions]`: `bucket4j = "0.13.0"` and to `[libraries]`:
```toml
bucket4j-spring-boot-starter = { module = "com.giffing.bucket4j.spring.boot.starter:bucket4j-spring-boot-starter", version.ref = "bucket4j" }
```
- [ ] **Step 2:** Verify `gradle :platform:acme-bom:help -q` → BUILD SUCCESSFUL. Commit:
```bash
git add gradle/libs.versions.toml
git commit -m "build: add bucket4j-spring-boot-starter alias"
```

---

## Task 2: acme-web idempotency filter (TDD) — starter improvement

**Files:** `IdempotencyStore.java`, `InMemoryIdempotencyStore.java`, `IdempotencyFilter.java`, register in `AcmeWebAutoConfiguration`, test `IdempotencyFilterTest.java`.

- [ ] **Step 1: failing test** — a MockMvc slice in the autoconfigure module proving replay. `starters/acme-web-spring-boot-autoconfigure/src/test/java/com/acme/web/IdempotencyFilterTest.java`:
```java
package com.acme.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class IdempotencyFilterTest {

    @Test
    void replaysStoredResponseForRepeatedKey() throws Exception {
        IdempotencyFilter filter = new IdempotencyFilter(new InMemoryIdempotencyStore());
        AtomicInteger handlerInvocations = new AtomicInteger();

        // a chain that writes a 201 body and counts invocations
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws java.io.IOException {
                handlerInvocations.incrementAndGet();
                HttpServletResponse http = (HttpServletResponse) res;
                http.setStatus(201);
                http.setContentType("application/json");
                http.getWriter().write("{\"id\":\"t-1\"}");
            }
        };

        MockHttpServletRequest first = post("key-1");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(first, firstResponse, chain);

        MockHttpServletRequest second = post("key-1");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, new MockFilterChain()); // chain not invoked on replay

        assertThat(handlerInvocations.get()).isEqualTo(1); // handler ran once
        assertThat(secondResponse.getStatus()).isEqualTo(201);
        assertThat(secondResponse.getContentAsString()).isEqualTo("{\"id\":\"t-1\"}");
    }

    @Test
    void passesThroughWhenNoKey() throws Exception {
        IdempotencyFilter filter = new IdempotencyFilter(new InMemoryIdempotencyStore());
        AtomicInteger invocations = new AtomicInteger();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                invocations.incrementAndGet();
            }
        };
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/orders");
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        assertThat(invocations.get()).isEqualTo(1);
    }

    private static MockHttpServletRequest post(String key) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/orders");
        req.addHeader("Idempotency-Key", key);
        return req;
    }
}
```
- [ ] **Step 2: run, FAIL** — `gradle :starters:acme-web-spring-boot-autoconfigure:test --tests "*IdempotencyFilterTest"` → FAIL.
- [ ] **Step 3: store interface + in-memory impl** —

`starters/acme-web-spring-boot-autoconfigure/src/main/java/com/acme/web/IdempotencyStore.java`:
```java
package com.acme.web;

import java.util.Optional;

/** Stores and replays completed responses keyed by an Idempotency-Key. Overridable (e.g. Redis-backed). */
public interface IdempotencyStore {

    Optional<StoredResponse> find(String key);

    void save(String key, StoredResponse response);

    record StoredResponse(int status, String contentType, byte[] body) {}
}
```
`starters/acme-web-spring-boot-autoconfigure/src/main/java/com/acme/web/InMemoryIdempotencyStore.java`:
```java
package com.acme.web;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Default in-process idempotency store. For multi-instance services, override with a shared store. */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final ConcurrentMap<String, StoredResponse> store = new ConcurrentHashMap<>();

    @Override
    public Optional<StoredResponse> find(String key) {
        return Optional.ofNullable(store.get(key));
    }

    @Override
    public void save(String key, StoredResponse response) {
        store.putIfAbsent(key, response);
    }
}
```
- [ ] **Step 4: filter** — `starters/acme-web-spring-boot-autoconfigure/src/main/java/com/acme/web/IdempotencyFilter.java`:
```java
package com.acme.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Replays a previously stored response when a request carries an {@code Idempotency-Key} already seen
 * (idempotent retries return the original result). Only applies to unsafe methods (POST/PATCH/PUT).
 * Responses with a 5xx status are not stored (transient failures should be retryable).
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final String HEADER = "Idempotency-Key";

    private final IdempotencyStore store;

    public IdempotencyFilter(IdempotencyStore store) {
        this.store = store;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key == null || !isUnsafe(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        var existing = store.find(key);
        if (existing.isPresent()) {
            writeStored(response, existing.get());
            return;
        }

        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        chain.doFilter(request, wrapper);
        if (wrapper.getStatus() < 500) {
            store.save(
                    key,
                    new IdempotencyStore.StoredResponse(
                            wrapper.getStatus(), wrapper.getContentType(), wrapper.getContentAsByteArray()));
        }
        wrapper.copyBodyToResponse();
    }

    private void writeStored(HttpServletResponse response, IdempotencyStore.StoredResponse stored) throws IOException {
        response.setStatus(stored.status());
        if (stored.contentType() != null) {
            response.setContentType(stored.contentType());
        }
        response.getOutputStream().write(stored.body());
    }

    private boolean isUnsafe(String method) {
        return "POST".equals(method) || "PATCH".equals(method) || "PUT".equals(method);
    }
}
```
- [ ] **Step 5: register in autoconfig** — in `AcmeWebAutoConfiguration`, add beans (guarded so an app can override):
```java
    @Bean
    @ConditionalOnMissingBean
    public IdempotencyStore acmeIdempotencyStore() {
        return new InMemoryIdempotencyStore();
    }

    @Bean
    @ConditionalOnProperty(prefix = "acme.web.idempotency", name = "enabled", havingValue = "true", matchIfMissing = true)
    public IdempotencyFilter acmeIdempotencyFilter(IdempotencyStore store) {
        return new IdempotencyFilter(store);
    }
```
(Add imports for `IdempotencyFilter`/`IdempotencyStore` are same package; `@ConditionalOnProperty` already imported.)
- [ ] **Step 6: run, PASS** — `gradle :starters:acme-web-spring-boot-autoconfigure:test --tests "*IdempotencyFilterTest"` → PASS.
- [ ] **Step 7: format + commit**
```bash
gradle :starters:acme-web-spring-boot-autoconfigure:spotlessApply
git add starters/acme-web-spring-boot-autoconfigure
git commit -m "feat(acme-web): idempotency filter (replays response per Idempotency-Key) + overridable store"
```

---

## Task 3: acme-web starter — add Bucket4j (rate limiting via properties)

**Files:** `starters/acme-web-spring-boot-starter/build.gradle.kts`.

- [ ] **Step 1:** Add to the starter's `dependencies { }`:
```kotlin
    api(libs.bucket4j.spring.boot.starter)
```
> Bucket4j's Spring Boot starter auto-configures a rate-limit servlet filter from `bucket4j.*` properties — a consumer enables/tunes it purely via config (no code). This is the `acme-web` rate-limit improvement.
- [ ] **Step 2:** Verify `gradle :starters:acme-web-spring-boot-starter:assemble` → BUILD SUCCESSFUL.
- [ ] **Step 3:** Commit:
```bash
git add starters/acme-web-spring-boot-starter/build.gradle.kts
git commit -m "feat(acme-web): bundle Bucket4j starter for property-driven rate limiting"
```

---

## Task 4: transfers REST edge (controller + DTOs + security)

**Files:** transfers `build.gradle.kts` (add acme-web + acme-security), `adapter/in/web/{TransferController, CreateTransferRequest, TransferErrorCode}.java`, `application.yaml` (security + bucket4j + idempotency).

- [ ] **Step 1: deps** — in `examples/acme-bank/transfers/build.gradle.kts` add:
```kotlin
    implementation(project(":starters:acme-web-spring-boot-starter"))
    implementation(project(":starters:acme-security-spring-boot-starter"))
    testImplementation(libs.spring.security.test)
```
- [ ] **Step 2: error code** — `.../adapter/in/web/TransferErrorCode.java`:
```java
package com.acme.bank.transfers.adapter.in.web;

import com.acme.web.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum TransferErrorCode implements ErrorCode {
    TRANSFER_NOT_FOUND(HttpStatus.NOT_FOUND, "Transfer not found");

    private final HttpStatus status;
    private final String title;

    TransferErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String defaultTitle() {
        return title;
    }
}
```
- [ ] **Step 3: request DTO** — `.../adapter/in/web/CreateTransferRequest.java`:
```java
package com.acme.bank.transfers.adapter.in.web;

import jakarta.validation.constraints.NotBlank;

public record CreateTransferRequest(
        @NotBlank String sourceAccountId, @NotBlank String destinationAccountId, @NotBlank String amount,
        @NotBlank String asset) {}
```
- [ ] **Step 4: controller** — `.../adapter/in/web/TransferController.java`:
```java
package com.acme.bank.transfers.adapter.in.web;

import an.awesome.pipelinr.Pipeline;
import com.acme.bank.transfers.application.InitiateTransferCommand;
import com.acme.bank.transfers.domain.TransferId;
import com.acme.bank.transfers.domain.Transfers;
import com.acme.money.Assets;
import com.acme.money.Money;
import com.acme.web.error.ApiException;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/transfers")
public class TransferController {

    private final Pipeline pipeline;
    private final Transfers transfers;

    public TransferController(Pipeline pipeline, Transfers transfers) {
        this.pipeline = pipeline;
        this.transfers = transfers;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> initiate(@Valid @RequestBody CreateTransferRequest request) {
        String transferId = UUID.randomUUID().toString();
        Money amount = Money.of(request.amount(), Assets.of(request.asset()));
        pipeline.send(new InitiateTransferCommand(
                transferId, request.sourceAccountId(), request.destinationAccountId(), amount, "api"));
        return Map.of("transferId", transferId, "status", "REQUESTED");
    }

    @GetMapping("/{id}")
    public Map<String, Object> status(@PathVariable String id) {
        var transfer = transfers
                .findById(new TransferId(id))
                .orElseThrow(() -> new ApiException(TransferErrorCode.TRANSFER_NOT_FOUND, Map.of("transferId", id)));
        return Map.of("transferId", transfer.id().value(), "status", transfer.status().name());
    }
}
```
> Note: `transfers.findById` currently rehydrates only REQUESTED state (BANK-2 stub) — adequate for BANK-4's GET (status is REQUESTED right after initiate); BANK-5 fixes full rehydration.
- [ ] **Step 5: config** — add to `examples/acme-bank/transfers/src/main/resources/application.yaml` (under `spring:` for security; top-level for bucket4j):
```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: ${KEYCLOAK_JWKS:http://localhost:8082/realms/bank/protocol/openid-connect/certs}
bucket4j:
  enabled: true
  filters:
    - cache-name: rate-limit-buckets
      url: /v1/.*
      strategy: first
      http-status-code: TOO_MANY_REQUESTS
      rate-limits:
        - cache-key: "@request.remoteAddr"
          bandwidths:
            - capacity: 100
              time: 1
              unit: minutes
              refill-speed: greedy
```
> The default permit list in `acme-security` already permits `/actuator/health/**`; `/v1/**` requires a JWT.
- [ ] **Step 6: compile** — `gradle :examples:acme-bank:transfers:compileJava` → BUILD SUCCESSFUL.
- [ ] **Step 7: commit**
```bash
gradle :examples:acme-bank:transfers:spotlessApply
git add examples/acme-bank/transfers
git commit -m "feat(transfers): secured REST edge (POST/GET /v1/transfers) with problem+json, OAuth2, idempotency, rate-limit"
```

---

## Task 5: REST edge ITs (security, idempotency, validation)

**Files:** `TransferApiIT.java`.

- [ ] **Step 1:** `examples/acme-bank/transfers/src/test/java/com/acme/bank/transfers/TransferApiIT.java` (MockMvc + Postgres Testcontainers; uses `jwt()` mock auth like demo's `SecurityIT`). Cover:
  - unauthenticated `POST /v1/transfers` → 401.
  - authenticated POST with a valid body → 202 + a `transferId`.
  - validation: blank `amount`/`asset` → 400 problem+json (`code=VALIDATION_FAILED`).
  - idempotency: two POSTs with the same `Idempotency-Key` (and jwt) → identical `transferId` in the body (the second is replayed), and only ONE transfer row persisted (assert via JdbcTemplate `count(*) from transfer`).
  - `GET /v1/transfers/{id}` for a just-created transfer → 200 with `status=REQUESTED`.
```java
package com.acme.bank.transfers;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.acme.test.PostgresTestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfiguration.class)
class TransferApiIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    private static final String VALID =
            "{\"sourceAccountId\":\"a\",\"destinationAccountId\":\"b\",\"amount\":\"100.00\",\"asset\":\"USD\"}";

    @Test
    void unauthenticatedIsRejected() throws Exception {
        mvc.perform(post("/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void initiatesTransfer() throws Exception {
        mvc.perform(post("/v1/transfers").with(jwt()).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.transferId").exists())
                .andExpect(jsonPath("$.status").value("REQUESTED"));
    }

    @Test
    void rejectsInvalidBody() throws Exception {
        String bad = "{\"sourceAccountId\":\"a\",\"destinationAccountId\":\"b\",\"amount\":\"\",\"asset\":\"USD\"}";
        mvc.perform(post("/v1/transfers").with(jwt()).contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void idempotencyKeyReplaysAndPersistsOnce() throws Exception {
        long before = jdbc.queryForObject("SELECT count(*) FROM transfer", Long.class);

        String first = mvc.perform(post("/v1/transfers")
                        .with(jwt())
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String second = mvc.perform(post("/v1/transfers")
                        .with(jwt())
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode a = mapper.readTree(first);
        JsonNode b = mapper.readTree(second);
        assertThat(b.get("transferId").asText()).isEqualTo(a.get("transferId").asText()); // replayed

        long after = jdbc.queryForObject("SELECT count(*) FROM transfer", Long.class);
        assertThat(after).isEqualTo(before + 1); // persisted exactly once
    }

    @Test
    void getsStatus() throws Exception {
        String body = mvc.perform(post("/v1/transfers")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = mapper.readTree(body).get("transferId").asText();

        mvc.perform(get("/v1/transfers/{id}", id).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REQUESTED"));
    }
}
```
> The idempotency test relies on the `IdempotencyFilter` replaying the first response for the repeated key — so the command runs once and exactly one `transfer` row is written. If the bucket4j filter or security ordering interferes, confirm the idempotency filter runs and the JWT is applied.
- [ ] **Step 2: run** — `gradle :examples:acme-bank:transfers:test` → all green (Transfer unit + externalization IT + ArchUnit + the new API IT).
- [ ] **Step 3: commit**
```bash
gradle :examples:acme-bank:transfers:spotlessApply
git add examples/acme-bank/transfers/src/test
git commit -m "test(transfers): REST edge IT — auth 401, initiate 202, validation 400, idempotency replay, status"
```

---

## Task 6: Full build + ADR

- [ ] **Step 1:** `gradle build` → BUILD SUCCESSFUL.
- [ ] **Step 2:** Create `docs/decisions/0016-rest-edge-idempotency-ratelimit.md` documenting the acme-web idempotency filter + Bucket4j rate-limit, and the gateway-collapsed-into-transfers decision.
- [ ] **Step 3:** Commit:
```bash
git add docs/decisions/0016-rest-edge-idempotency-ratelimit.md
git commit -m "docs: ADR 0016 REST edge idempotency + rate limiting"
```

---

## Done criteria for BANK-4

- `gradle build` green; acme-web idempotency filter unit-tested; transfers REST edge IT green (401/202/400/idempotency-replay/status).
- acme-web ships the idempotency filter (overridable store) + Bucket4j rate-limit (property-driven).
- transfers `POST/GET /v1/transfers` secured (OAuth2), problem+json, idempotent, rate-limited.

---

## Self-review notes

- **Spec coverage (§5 gateway/acme-web):** REST edge (POST/GET) ✓, OAuth2 ✓ (acme-security), problem+json ✓ (acme-web), springdoc — already in acme-web starter, **idempotency filter ✓ (the deferred improvement)**, **rate-limit ✓ (Bucket4j, deferred improvement)**, projection/read-model = transfers' own status (the gateway role collapsed into transfers, documented). Cache for status reads: deferred (status is a simple PK lookup).
- **Type consistency:** `IdempotencyFilter`/`IdempotencyStore`/`StoredResponse`/`InMemoryIdempotencyStore`; `TransferController` uses `InitiateTransferCommand` + `Pipeline` + `Transfers` port + `Money.of`/`Assets.of` + `ApiException`/`ErrorCode` from acme-web.
- **No placeholders.** Concrete filter/controller/IT.
- **Risk:** filter ordering (idempotency vs bucket4j vs security) — Spring registers them; the IT verifies the end-to-end behavior (401 from security, 202 + replay from idempotency). The idempotency store is in-memory (single instance) — documented; multi-instance needs a shared store.
