# BANK-10: functional + end-to-end tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** Prove the whole acme-bank system works through its public edge: a dedicated `e2e` module brings up the entire `compose.bank.yaml` stack (all 5 services + Postgres + Redpanda/SR + Keycloak + Redis + otel-lgtm) via Testcontainers, obtains a REAL Keycloak token, and drives the full money-movement saga through the gateway — asserting end-state (COMPLETED, balances moved, ledger Σ=0, notification stored). Plus a thin functional-contract test pass per service.

**Architecture:** A test-only Gradle module `examples/acme-bank/e2e` using Testcontainers `ComposeContainer` (local compose, build the service images from the BANK-9 Dockerfiles). Tests talk to the gateway over HTTP (java.net.http) with a bearer token fetched from the Keycloak container via password grant. Tagged `e2e` and EXCLUDED from the default `gradle build` (heavy, needs image pulls) — run via `gradle :examples:acme-bank:e2e:e2eTest`. Functional tests reuse the existing per-service API ITs (already black-box over HTTP) and fill any gaps.

**Tech Stack:** Testcontainers `ComposeContainer`, JUnit5 (`@Tag("e2e")`), Awaitility, java.net.http, Keycloak password grant.

> Spec: `docs/superpowers/specs/2026-06-18-acme-bank-production-grade-design.md` §5. Builds on BANK-0..9 (the deployable stack).
> **Environment (every task):** work from `/Users/npden4ik/Projects/java-boilerplate`, on `master`; system `gradle` (8.14) NEVER `./gradlew`; JDK 21; Docker up. Postgres/Redpanda/Redis cached; Keycloak/otel-lgtm pull slowly first-run — the e2e test itself may be slow/skipped in a constrained env; it MUST compile and be correctly wired, and run when images are available. `gradle <module>:spotlessApply` before commits.

---

## Task 1: functional-contract pass (per service) — close gaps

**Files:** review/extend `examples/acme-bank/{accounts,transfers,gateway}/src/test/.../**ApiIT.java` (already exist from BANK-7/8). Add only what's missing.

- [ ] **Step 1:** Audit the existing API ITs (`AccountApiIT`, `TransferQueryIT`, `TransferApiIT`, gateway `TransferControllerIT`/`AccountProxyIT`) for functional-contract coverage: success shapes, problem+json on errors, 401 unauth, idempotency replay, validation 400, paging clamp. Note any gap.
- [ ] **Step 2:** Add the missing functional assertions as new test methods in the existing ITs (do NOT create parallel modules). Concretely ensure each of these is asserted SOMEWHERE:
  - gateway `POST /v1/transfers` replays the SAME 202 body for a repeated `Idempotency-Key` (idempotency filter) — add to `TransferControllerIT` if absent.
  - gateway `POST /v1/transfers` with a malformed body (missing `amount`) → 400 problem+json (`type`/`title`/`status` present).
  - accounts `POST /v1/accounts` with a negative/zero amount string or bad asset → 400.
  - a `GET` with an unknown id → 404 problem+json with `application/problem+json` content type.
- [ ] **Step 3:** Run the touched modules' tests → green.
- [ ] **Step 4: commit**
```bash
gradle :examples:acme-bank:gateway:spotlessApply :examples:acme-bank:accounts:spotlessApply :examples:acme-bank:transfers:spotlessApply
git add examples/acme-bank
git commit -m "test(bank): functional-contract assertions — idempotency replay, validation 400, problem+json 404"
```

---

## Task 2: e2e module scaffold + Keycloak token helper

**Files:** `settings.gradle.kts` (include), `examples/acme-bank/e2e/build.gradle.kts`, `examples/acme-bank/e2e/src/test/java/com/acme/bank/e2e/StackContainers.java`, `.../KeycloakToken.java`, `.../HttpJson.java`.

- [ ] **Step 1:** `settings.gradle.kts`: `include(":examples:acme-bank:e2e")`.
- [ ] **Step 2:** `e2e/build.gradle.kts` — a test-only module (no `main`). Plugins: the java conventions (NOT spring-boot — no app). Deps (testImplementation): `org.testcontainers:testcontainers`, `org.testcontainers:junit-jupiter`, `org.awaitility:awaitility`, JUnit5, `com.fasterxml.jackson.core:jackson-databind` (catalog accessors where they exist). Register a separate test task `e2eTest` that runs ONLY `@Tag("e2e")` and is NOT wired into `check`/`build`:
```kotlin
val e2eTest = tasks.register<Test>("e2eTest") {
    description = "Full-stack e2e (requires Docker + image pulls)"
    group = "verification"
    useJUnitPlatform { includeTags("e2e") }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    shouldRunAfter(tasks.test)
    // bring the service jars up to date so ComposeContainer can build images
    dependsOn(
        ":examples:acme-bank:gateway:bootJar",
        ":examples:acme-bank:transfers:bootJar",
        ":examples:acme-bank:accounts:bootJar",
        ":examples:acme-bank:antifraud:bootJar",
        ":examples:acme-bank:notifications:bootJar",
    )
}
tasks.named("test") { useJUnitPlatform { excludeTags("e2e") } } // default build skips e2e
```
- [ ] **Step 3:** `StackContainers` — a JUnit5 extension / base class wrapping `ComposeContainer(new File("../compose.bank.yaml"))` with `.withLocalCompose(true).withBuild(true)` (build images from the BANK-9 Dockerfiles), exposing the gateway (8080) and Keycloak (8082) with `Wait.forHttp("/actuator/health/readiness")` (gateway) / `Wait.forHttp("/realms/bank")` (keycloak), generous startup timeout (e.g. 5 min). Provide accessors for the gateway base URL + Keycloak base URL.
- [ ] **Step 4:** `KeycloakToken.fetch(keycloakBaseUrl, "alice", "alice")` — POST `…/realms/bank/protocol/openid-connect/token` with `grant_type=password&client_id=bank-gateway&username=…&password=…`, parse `access_token`. `HttpJson` — tiny java.net.http helper (GET/POST JSON with a bearer + an `Idempotency-Key`, returns status + parsed `JsonNode`).
- [ ] **Step 5:** `gradle :examples:acme-bank:e2e:compileTestJava` → compiles. (Do NOT run `e2eTest` yet if image pulls are slow — report.)
- [ ] **Step 6: commit**
```bash
gradle :examples:acme-bank:e2e:spotlessApply
git add settings.gradle.kts examples/acme-bank/e2e
git commit -m "test(e2e): scaffold full-stack e2e module (ComposeContainer + Keycloak token + http helpers, e2e-tagged)"
```

---

## Task 3: e2e — happy-path saga through the gateway

**Files:** `e2e/src/test/java/com/acme/bank/e2e/TransferSagaE2eTest.java`.

- [ ] **Step 1:** `@Tag("e2e")` `TransferSagaE2eTest` using `StackContainers`:
  - fetch a token (`alice`).
  - `POST /v1/accounts` twice (source with `initialDeposit` 1000.00 USD, destination 0) → capture both accountIds + ibans. (201)
  - `POST /v1/transfers` `{source, destination, amount: 250.00 USD}` with a bearer + `Idempotency-Key` → 202, capture `transferId`.
  - `Awaitility.await().atMost(90s)` polling `GET /v1/transfers/{id}` until `status == "COMPLETED"` (the full saga: requested→screened→posting→ledger-posted→completed, reflected in the gateway projection).
  - assert source balance == 750.00 USD and destination == 250.00 USD (`GET /v1/accounts/{id}/balance`).
  - assert the destination statement shows the +250.00 credit (`GET …/statement`).
- [ ] **Step 2:** Run `gradle :examples:acme-bank:e2e:e2eTest --tests "*TransferSagaE2eTest"` IF images are available. If Keycloak/otel-lgtm pulls block, report that the test is wired+compiles and must be run where images are cached; do NOT hang the build. (The default `gradle build` excludes it regardless.)
- [ ] **Step 3: commit**
```bash
gradle :examples:acme-bank:e2e:spotlessApply
git add examples/acme-bank/e2e
git commit -m "test(e2e): happy-path transfer saga through gateway with real Keycloak token (COMPLETED + balances + statement)"
```

---

## Task 4: e2e — rejected, idempotency, auth-negative

**Files:** `e2e/.../TransferRejectedE2eTest.java`, `.../IdempotencyE2eTest.java`, `.../AuthE2eTest.java` (or methods within one class — keep the stack shared via a single container per class to limit startup cost; prefer ONE `SagaScenariosE2eTest` with multiple `@Test` methods sharing the container).

- [ ] **Step 1: rejected** — `POST /v1/transfers` with an amount above the antifraud limit (e.g. 25000.00 USD, matching the antifraud `AMOUNT_LIMIT` rule) → await `GET /v1/transfers/{id}` status `FAILED` with the failure reason surfaced; assert source/destination balances UNCHANGED (no ledger movement).
- [ ] **Step 2: idempotency** — `POST /v1/transfers` twice with the SAME `Idempotency-Key` + identical body → both 202 with the SAME `transferId`; after settling, `GET /v1/transfers?accountId=src` shows exactly ONE transfer for that key (no double-spend); source debited once.
- [ ] **Step 3: auth-negative** — `POST /v1/transfers` with NO bearer → 401; with a malformed/expired bearer → 401. (Optional: a token lacking a required role → 403 if role-based rules exist; else skip.)
- [ ] **Step 4:** Run (if images available) / report. 
- [ ] **Step 5: commit**
```bash
gradle :examples:acme-bank:e2e:spotlessApply
git add examples/acme-bank/e2e
git commit -m "test(e2e): rejected-transfer (antifraud), idempotency no-double-spend, auth-negative scenarios"
```

---

## Task 5: CI wiring + README + ADR

**Files:** CI workflow (if `.github/workflows/*` exists — add an `e2e` job; else document), `examples/acme-bank/README.md` (e2e section), `docs/decisions/0021-e2e-strategy.md`.

- [ ] **Step 1:** If a CI workflow exists, add a separate (manual/nightly or PR-gated) job that runs `gradle :examples:acme-bank:e2e:e2eTest` (Docker-in-CI), kept off the fast `build` job. Also add the Avro schema-registry compatibility gate + `bankJars` to CI if not present (per the production spec §6). If no CI dir exists, document the intended jobs in the README.
- [ ] **Step 2:** README "End-to-end tests" section: what the e2e covers, how to run (`gradle :examples:acme-bank:e2e:e2eTest`), the image-pull caveat, and that it's excluded from the default build.
- [ ] **Step 3:** ADR `0021-e2e-strategy.md` — decisions: ComposeContainer over a hand-rolled network (tests exactly what ships); real Keycloak token via password grant (not a mocked decoder) so the whole auth path is exercised; e2e tagged + excluded from the default build (cost/flakiness isolation); the scenarios chosen (happy/rejected/idempotency/auth) and what each proves end-to-end. Consequences + alternatives (in-JVM `@SpringBootTest` multi-context; k8s-based e2e).
- [ ] **Step 4: commit**
```bash
git add examples/acme-bank/README.md docs/decisions/0021-e2e-strategy.md .github 2>/dev/null || git add examples/acme-bank/README.md docs/decisions/0021-e2e-strategy.md
git commit -m "docs: e2e README + ADR 0021 e2e strategy (+ CI e2e job)"
```

---

## Task 6: full build

- [ ] **Step 1:** `gradle build` → BUILD SUCCESSFUL (e2e EXCLUDED — its `test` task excludes `@Tag("e2e")`; only `compileTestJava` runs in the default build, proving the e2e wiring compiles).
- [ ] **Step 2:** Confirm `gradle :examples:acme-bank:e2e:e2eTest` is the on-demand entrypoint and is NOT triggered by `build`/`check`.
- [ ] **Step 3:** If a live run was possible, report the e2e result; otherwise state clearly that the e2e compiles + is wired and runs where Keycloak/otel-lgtm images are available.

---

## Done criteria for BANK-10

- Functional-contract assertions (idempotency replay, validation 400, problem+json 404, 401) covered across the service API ITs.
- `e2e` module: ComposeContainer brings up the full stack; a real Keycloak token drives the gateway.
- Happy-path saga → COMPLETED + balances moved + ledger reflected + statement credit.
- Rejected (antifraud) → FAILED, no movement; idempotency → one transfer; auth-negative → 401.
- e2e is `@Tag("e2e")`, excluded from `gradle build`, runnable via `:e2e:e2eTest`.
- README e2e section + ADR 0021; CI e2e job (or documented).
- `gradle build` green.

---

## Self-review notes

- **Spec coverage:** §5 functional (T1) ✓; e2e full-stack scaffold (T2), happy-path (T3), rejected/idempotency/auth (T4) ✓; CI/README/ADR (T5) ✓.
- **Type consistency:** `StackContainers` (ComposeContainer, gateway/keycloak accessors), `KeycloakToken.fetch`, `HttpJson` (bearer + Idempotency-Key) used by all e2e tests; `e2eTest` task excludes-from-build via tag.
- **No placeholders:** scenarios concrete (amounts, statuses, endpoints). Antifraud limit amount (25000) matches the existing `ScreeningIT` `AMOUNT_LIMIT` threshold — verify the real threshold in `antifraud` and use a value above it.
- **Risk:** ComposeContainer `withBuild(true)` needs the bootJars built (task `dependsOn` handles it) and Docker daemon access; Keycloak `iss` must match what the services validate (BANK-9's single-issuer rule) or every authed call 401s — the e2e is the real proof of that rule. Image pulls may make `e2eTest` slow/unrunnable in a constrained env; it stays OUT of the default build so it never blocks `gradle build`. Share ONE container per test class to bound startup cost.
