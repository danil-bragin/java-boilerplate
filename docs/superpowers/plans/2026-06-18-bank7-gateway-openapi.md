# BANK-7: gateway + spec-first OpenAPI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** Add the `gateway` edge service driven by a hand-written OpenAPI 3.1 contract (spec-first: contract → generated Spring server interfaces → controllers), serving swagger-ui from the contract, fronting `transfers` via a resilience4j `RestClient`, and answering `GET /v1/transfers/{id}` from a CQRS read-model projection built by consuming the saga's Avro events (inbox-deduped).

**Architecture:** Hexagonal edge. Web adapter = generated `*Api` interfaces implemented by controllers + acme-web edge starters (OAuth2/idempotency/rate-limit/problem+json). Application = thin orchestration. Out-adapters = `TransfersRestClient` (resilience4j) + `TransferStatusProjection` (Avro consumer → `transfer_view` upsert). No money invariants in the gateway.

**Tech Stack:** Java 21, Spring Boot 3.5.6, `org.openapi.generator` (spring, interfaceOnly), springdoc-openapi-ui, Spring `RestClient`, Resilience4j, acme-messaging (Avro inbox), Flyway, Testcontainers (Postgres + Redpanda).

> Spec: `docs/superpowers/specs/2026-06-18-acme-bank-production-grade-design.md` §2. Builds on BANK-0..6.
> **Environment (every task):** work from `/Users/npden4ik/Projects/java-boilerplate`; system `gradle` (8.14) NEVER `./gradlew`; JDK 21; Docker up, Postgres/Redpanda cached. `gradle :examples:acme-bank:gateway:spotlessApply` before each commit.

---

## Task 1: module scaffold + openapi-generator wiring

**Files:** `settings.gradle.kts` (include), `examples/acme-bank/gateway/build.gradle.kts`, `gradle/libs.versions.toml` (openapi-generator plugin), `gateway/src/main/resources/openapi/bank-api.yaml`, `gateway/src/main/java/com/acme/bank/gateway/GatewayApplication.java`, `gateway/src/main/resources/application.yaml`.

- [ ] **Step 1:** Add to `settings.gradle.kts`: `include(":examples:acme-bank:gateway")`.
- [ ] **Step 2:** Add the openapi-generator plugin to `gradle/libs.versions.toml`:
```toml
[versions]
openapi-generator = "7.9.0"
[plugins]
openapi-generator = { id = "org.openapi.generator", version.ref = "openapi-generator" }
```
- [ ] **Step 3:** Write the OpenAPI contract `gateway/src/main/resources/openapi/bank-api.yaml` (OpenAPI 3.0.3 — the generator's most stable target). Minimum for BANK-7 (accounts ops are declared but their controllers come in BANK-8; declare only the transfer ops here to keep the generated interface implementable now, OR declare all and implement a 501 stub for account ops — choose: declare transfer ops + `GET /v1/transfers/{id}` + list; account ops deferred to BANK-8's contract extension):
```yaml
openapi: 3.0.3
info:
  title: acme-bank API
  version: 1.0.0
  description: Money-movement API. Spec-first — this contract is the source of truth.
servers:
  - url: http://localhost:8080
security:
  - bearerAuth: []
paths:
  /v1/transfers:
    post:
      tags: [transfers]
      operationId: createTransfer
      summary: Initiate a transfer (idempotent via Idempotency-Key)
      parameters:
        - in: header
          name: Idempotency-Key
          required: true
          schema: { type: string }
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/CreateTransferRequest' }
      responses:
        '202':
          description: Accepted
          content:
            application/json:
              schema: { $ref: '#/components/schemas/TransferAccepted' }
        '400': { $ref: '#/components/responses/Problem' }
        '401': { description: Unauthorized }
        '409': { $ref: '#/components/responses/Problem' }
    get:
      tags: [transfers]
      operationId: listTransfers
      parameters:
        - { in: query, name: accountId, schema: { type: string } }
        - { in: query, name: status, schema: { type: string } }
        - { in: query, name: page, schema: { type: integer, default: 0 } }
        - { in: query, name: size, schema: { type: integer, default: 20 } }
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema: { $ref: '#/components/schemas/TransferPage' }
  /v1/transfers/{id}:
    get:
      tags: [transfers]
      operationId: getTransfer
      parameters:
        - { in: path, name: id, required: true, schema: { type: string } }
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema: { $ref: '#/components/schemas/TransferView' }
        '404': { $ref: '#/components/responses/Problem' }
components:
  securitySchemes:
    bearerAuth: { type: http, scheme: bearer, bearerFormat: JWT }
  responses:
    Problem:
      description: RFC 9457 problem+json
      content:
        application/problem+json:
          schema: { $ref: '#/components/schemas/Problem' }
  schemas:
    Money:
      type: object
      required: [value, asset]
      properties:
        value: { type: string, example: "100.00", description: Exact decimal string, no float }
        asset: { type: string, example: USD }
    CreateTransferRequest:
      type: object
      required: [sourceAccountId, destinationAccountId, amount]
      properties:
        sourceAccountId: { type: string }
        destinationAccountId: { type: string }
        amount: { $ref: '#/components/schemas/Money' }
        reference: { type: string }
    TransferAccepted:
      type: object
      required: [transferId, status]
      properties:
        transferId: { type: string }
        status: { type: string }
    TransferView:
      type: object
      required: [transferId, status, amount, sourceAccountId, destinationAccountId]
      properties:
        transferId: { type: string }
        status: { type: string, enum: [REQUESTED, SCREENING, APPROVED, POSTING, COMPLETED, FAILED] }
        amount: { $ref: '#/components/schemas/Money' }
        sourceAccountId: { type: string }
        destinationAccountId: { type: string }
        failureReason: { type: string }
        createdAt: { type: string, format: date-time }
        updatedAt: { type: string, format: date-time }
    TransferPage:
      type: object
      properties:
        content: { type: array, items: { $ref: '#/components/schemas/TransferView' } }
        page: { type: integer }
        size: { type: integer }
        totalElements: { type: integer, format: int64 }
    Problem:
      type: object
      properties:
        type: { type: string }
        title: { type: string }
        status: { type: integer }
        detail: { type: string }
        instance: { type: string }
```
- [ ] **Step 4:** `gateway/build.gradle.kts` — apply the convention plugin + openapi-generator, configure interfaceOnly spring generation, add deps:
```kotlin
plugins {
    id("com.acme.java-library-conventions") // or the bank service convention used by transfers
    alias(libs.plugins.openapi.generator)
}

dependencies {
    implementation(project(":examples:acme-bank:bank-contracts"))
    implementation(project(":starters:acme-web-spring-boot-starter"))
    implementation(project(":starters:acme-security-spring-boot-starter"))
    implementation(project(":starters:acme-ratelimit-spring-boot-starter"))
    implementation(project(":starters:acme-observability-spring-boot-starter"))
    implementation(project(":starters:acme-messaging-spring-boot-starter"))
    implementation(project(":starters:acme-persistence-spring-boot-starter"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("io.github.resilience4j:resilience4j-spring-boot3")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")
    // generated-interface support
    implementation("io.swagger.core.v3:swagger-annotations:2.2.22")
    implementation("org.openapitools:jackson-databind-nullable:0.2.6")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation(project(":starters:acme-test-support"))
}

openApiGenerate {
    generatorName.set("spring")
    inputSpec.set("$rootDir/examples/acme-bank/gateway/src/main/resources/openapi/bank-api.yaml")
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asString)
    apiPackage.set("com.acme.bank.gateway.api")
    modelPackage.set("com.acme.bank.gateway.api.dto")
    configOptions.set(mapOf(
        "interfaceOnly" to "true",
        "useSpringBoot3" to "true",
        "useTags" to "true",
        "documentationProvider" to "none",
        "openApiNullable" to "false",
        "skipDefaultInterface" to "false",
    ))
}

sourceSets.main { java.srcDir(layout.buildDirectory.dir("generated/openapi/src/main/java")) }
tasks.named("compileJava") { dependsOn("openApiGenerate") }
tasks.named("spotlessJava") { dependsOn("openApiGenerate") }
```
(Match the actual convention-plugin id used by sibling bank services — read `examples/acme-bank/transfers/build.gradle.kts` for the exact `plugins {}` block and resilience4j/springdoc coordinates already in the catalog; prefer catalog accessors `libs.…` where they exist.)
- [ ] **Step 5:** `GatewayApplication.java` (standard `@SpringBootApplication`) + `application.yaml` (port 8080, datasource, kafka consumer w/ Avro + SR url, oauth2 resourceserver jwk-set-uri, resilience4j instance `transfers`, springdoc `swagger-ui.url=/openapi/bank-api.yaml`, `spring.modulith` not needed — gateway has no outbox).
- [ ] **Step 6:** `gradle :examples:acme-bank:gateway:compileJava` → generates interfaces + compiles (no controllers yet → app has no beans implementing the Api, that's fine until Task 2). Verify `build/generated/openapi/.../api/TransfersApi.java` exists.
- [ ] **Step 7: commit**
```bash
gradle :examples:acme-bank:gateway:spotlessApply
git add settings.gradle.kts gradle/libs.versions.toml examples/acme-bank/gateway
git commit -m "feat(gateway): scaffold spec-first gateway module (OpenAPI contract + openapi-generator interfaceOnly)"
```

---

## Task 2: transfer-status projection (read model, Avro consumer) — TDD

**Files:** `gateway/.../projection/TransferView.java` (JPA entity), `TransferViewRepository.java`, `TransferStatusProjection.java` (listeners), `db/migration/postgresql/V1__gateway.sql` (`transfer_view` + `processed_messages`), `TransferProjectionIT.java`.

- [ ] **Step 1:** `V1__gateway.sql`:
```sql
CREATE TABLE transfer_view (
    transfer_id            VARCHAR(64) PRIMARY KEY,
    status                 VARCHAR(32) NOT NULL,
    status_rank            INT NOT NULL,
    amount_value           NUMERIC(38,18) NOT NULL,
    amount_asset           VARCHAR(16) NOT NULL,
    source_account_id      VARCHAR(64) NOT NULL,
    destination_account_id VARCHAR(64) NOT NULL,
    failure_reason         VARCHAR(64),
    created_at             TIMESTAMPTZ NOT NULL,
    updated_at             TIMESTAMPTZ NOT NULL
);
CREATE TABLE processed_messages (
    listener   VARCHAR(128) NOT NULL,
    message_id VARCHAR(128) NOT NULL,
    PRIMARY KEY (listener, message_id)
);
```
- [ ] **Step 2: failing IT** — `TransferProjectionIT` (Postgres+Redpanda): produce `TransferRequested` then `TransferCompleted` (Avro) for one transferId; assert `transfer_view` row ends with status COMPLETED, amount preserved; produce an older `TransferRequested` again (redelivery) → status stays COMPLETED (rank guard), one inbox row per (listener,eventType,id).
- [ ] **Step 3: run, FAIL.**
- [ ] **Step 4:** `TransferView` JPA entity + repository; `TransferStatusProjection` with one `@KafkaListener` method per event type (`transfer-requested`, `transfer-screened`, `transfer-completed`, `transfer-failed`), each `@Transactional`, each guarded by `inbox.firstTime("gateway-projection:" + eventType, transferId)`, each upserting `transfer_view` with a `status_rank` (REQUESTED=0,SCREENING=1,APPROVED=2,POSTING=3,COMPLETED=4,FAILED=4) and only advancing when the incoming rank ≥ stored rank. Map Avro Money → `MoneyAmount` columns. Reuse the antifraud listener wiring pattern (`stringKafkaListenerContainerFactory` / Avro deserializer + SR url from `application.yaml`). The consumer config mirrors transfers' consume-side.
- [ ] **Step 5: run, PASS** — `gradle :examples:acme-bank:gateway:test --tests "*TransferProjectionIT"`.
- [ ] **Step 6: commit**
```bash
gradle :examples:acme-bank:gateway:spotlessApply
git add examples/acme-bank/gateway
git commit -m "feat(gateway): transfer-status read model projection (Avro inbox-deduped, rank-guarded upsert)"
```

---

## Task 3: controllers + RestClient to transfers (resilience4j) — TDD

**Files:** `gateway/.../web/TransferController.java` (implements generated `TransfersApi`), `gateway/.../client/TransfersRestClient.java`, `gateway/.../application/{SubmitTransfer,GetTransfer,ListTransfers}.java`, `RestClientConfig.java`, `TransferControllerIT.java`.

- [ ] **Step 1: failing IT** — `TransferControllerIT` (`webEnvironment=RANDOM_PORT`, Postgres+Redpanda, JWT via `spring-security-test` `jwt()` post-processor or a test JWKS). Cases:
  - `POST /v1/transfers` with Idempotency-Key + bearer, transfers stubbed (WireMock or a `@TestConfiguration` fake `TransfersRestClient`) returns the created id → 202 `{transferId,status:"REQUESTED"}`.
  - missing bearer → 401.
  - `GET /v1/transfers/{id}` after seeding a `transfer_view` row → 200 view.
  - missing id → 404 problem+json.
- [ ] **Step 2: run, FAIL.**
- [ ] **Step 3:** Implement:
  - `TransfersRestClient` — Spring `RestClient` (base url from config) `POST /v1/transfers` forwarding the request + Idempotency-Key, annotated `@CircuitBreaker(name="transfers")` `@Retry(name="transfers")` (resilience4j-spring) with a fallback that throws a `GatewayUnavailableException` → mapped to 503 problem+json by a `@RestControllerAdvice`.
  - `SubmitTransfer` (calls the client), `GetTransfer`/`ListTransfers` (read the projection).
  - `TransferController implements TransfersApi` — maps generated DTOs ↔ application types; returns `ResponseEntity<TransferAccepted>` 202 etc. Translate domain/projection → `TransferView` DTO.
  - `@RestControllerAdvice` mapping `GatewayUnavailableException`→503, not-found→404, validation→400, all problem+json (reuse acme-web's problem support if it exposes a base handler).
- [ ] **Step 4: run, PASS** — `gradle :examples:acme-bank:gateway:test --tests "*TransferControllerIT"`.
- [ ] **Step 5: commit**
```bash
gradle :examples:acme-bank:gateway:spotlessApply
git add examples/acme-bank/gateway
git commit -m "feat(gateway): transfer controllers (generated API) + resilience4j RestClient to transfers + problem+json"
```

---

## Task 4: spec-first guards — swagger-ui served from contract + drift test

**Files:** `gateway/src/main/resources/static/openapi/bank-api.yaml` (copied/symlinked for serving) OR springdoc config, `OpenApiContractTest.java`.

- [ ] **Step 1:** Serve the hand-written contract: make `bank-api.yaml` resolvable at `/openapi/bank-api.yaml` (place a copy under `src/main/resources/static/openapi/` OR add a `@GetMapping("/openapi/bank-api.yaml")` returning the classpath resource). Set `springdoc.swagger-ui.url=/openapi/bank-api.yaml` so swagger-ui renders the source contract, and `springdoc.api-docs.enabled=true` keeps the code-introspected `/v3/api-docs` available for the drift test.
- [ ] **Step 2: contract drift test** — `OpenApiContractTest` (`@SpringBootTest` RANDOM_PORT): fetch `/v3/api-docs`, parse operationIds, assert it contains `{createTransfer,getTransfer,listTransfers}` (the contract's operations are all implemented). Also assert `/openapi/bank-api.yaml` is served 200 with `openapi: 3.0.3`. (The controllers `implements TransfersApi` so any contract op without an impl is a compile failure — this test guards the served-docs side.)
- [ ] **Step 3: run, PASS.**
- [ ] **Step 4:** README snippet in `examples/acme-bank/gateway/README.md` — spec-first flow (edit `bank-api.yaml` → regenerate → implement), swagger-ui URL, projection/CQRS note, resilience4j behavior.
- [ ] **Step 5: commit**
```bash
gradle :examples:acme-bank:gateway:spotlessApply
git add examples/acme-bank/gateway
git commit -m "feat(gateway): serve spec-first swagger-ui from hand-written contract + drift guard test"
```

---

## Task 5: full build + ADR

- [ ] **Step 1:** `gradle build` → BUILD SUCCESSFUL (gateway compiles generated interfaces, all ITs pass on Testcontainers).
- [ ] **Step 2:** ADR `docs/decisions/0018-spec-first-gateway.md` — decision: spec-first OpenAPI (contract → generated server interfaces, interfaceOnly), BFF gateway as the single edge, CQRS status projection over saga events (read model, not a money optimization — distinct from the no-materialized-balance rule), resilience4j around the downstream call. Consequences + alternatives (code-first springdoc; direct-to-transfers no gateway).
- [ ] **Step 3: commit**
```bash
git add docs/decisions/0018-spec-first-gateway.md
git commit -m "docs: ADR 0018 spec-first gateway + CQRS status projection"
```

---

## Done criteria for BANK-7

- `gateway` module builds from a hand-written OpenAPI contract via openapi-generator (interfaceOnly); controllers implement the generated `*Api`.
- swagger-ui serves the hand-written contract; a drift test guards docs↔impl.
- `POST /v1/transfers` forwards to transfers via a resilience4j RestClient (503 on open circuit); `GET /v1/transfers/{id}`/list serve a CQRS read model built by inbox-deduped, rank-guarded Avro consumers.
- OAuth2 + Idempotency-Key + rate-limit + problem+json on the edge.
- `gradle build` green; ADR 0018 written.

---

## Self-review notes

- **Spec coverage:** §2.1 contract+codegen+swagger-ui (T1,T4) ✓; §2.2 edge+RestClient+projection (T2,T3) ✓; §2.3 status rank guard (T2) ✓.
- **Type consistency:** generated `TransfersApi`/`TransferView`/`TransferAccepted`/`CreateTransferRequest` DTOs used by `TransferController`; `inbox.firstTime` key `"gateway-projection:"+eventType`; `status_rank` map shared between entity + listeners.
- **No placeholders:** account ops intentionally deferred to BANK-8 (contract declares only transfer ops here so generated interface is fully implementable now). Noted, not a gap.
- **Risk:** openapi-generator 7.9.0 + Boot 3.5 — `interfaceOnly`+`useSpringBoot3` generates `jakarta` + `ResponseEntity` signatures; ensure swagger-annotations + jackson-databind-nullable on classpath. The `compileJava dependsOn openApiGenerate` + sourceSet srcDir wires generation into the build. Resilience4j fallback → 503 mapping verified by IT.
