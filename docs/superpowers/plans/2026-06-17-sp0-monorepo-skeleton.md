# SP-0: Monorepo Skeleton + Vertical Slice — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the Gradle monorepo skeleton (build-logic convention plugins + `java-platform` BOM + version catalog), one real custom Spring Boot starter (`acme-web` — RFC 9457 problem+json error model + bean-validation mapping), one deletable example service that consumes it, and a green CI workflow — proving the starter auto-configures end-to-end.

**Architecture:** Gradle (Kotlin DSL) multi-module monorepo. Cross-cutting concerns ship as `*-spring-boot-autoconfigure` + thin `*-spring-boot-starter` module pairs, discovered via `META-INF/spring/...AutoConfiguration.imports`, guarded by `@ConditionalOn*`. Shared build config lives in a `build-logic` included build. Versions are centralized in `gradle/libs.versions.toml`; a published `acme-bom` re-exports `spring-boot-dependencies` for consumers. The example service applies the Spring Boot plugin and depends on the starter; an integration test asserts the auto-configured handler emits `application/problem+json`.

**Tech Stack:** Java 21, Spring Boot 3.5.6, Gradle 8.14 (Kotlin DSL), JUnit 5 + AssertJ + Spring MockMvc, Spotless (palantirJavaFormat), GitHub Actions.

> Spec: `docs/superpowers/specs/2026-06-17-acme-boilerplate-design.md` (§2, §3, §4, §5 acme-web, §9 CI).
> Version pins (`spring-boot`, `spotless`, action majors) are adoption-time adjustable per spec §12 — values below are concrete and known-working.

---

## File structure (created by this plan)

```
acme-boilerplate/
├── .gitignore
├── .github/workflows/ci.yml
├── settings.gradle.kts                       root build: includes build-logic + modules
├── gradle/libs.versions.toml                 version catalog
├── gradle/wrapper/…                           Gradle 8.14 wrapper
├── build-logic/
│   ├── settings.gradle.kts
│   ├── build.gradle.kts                       kotlin-dsl + spotless plugin on classpath
│   └── src/main/kotlin/acme.java-conventions.gradle.kts
├── platform/acme-bom/build.gradle.kts        java-platform BOM
├── starters/
│   ├── acme-web-spring-boot-autoconfigure/
│   │   ├── build.gradle.kts
│   │   └── src/
│   │       ├── main/java/com/acme/web/error/{ErrorCode,ApiException,ProblemExceptionHandler}.java
│   │       ├── main/java/com/acme/web/autoconfigure/AcmeWebAutoConfiguration.java
│   │       ├── main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
│   │       └── test/java/com/acme/web/error/ProblemExceptionHandlerTest.java
│   └── acme-web-spring-boot-starter/build.gradle.kts
├── examples/demo-service/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/com/acme/demo/{DemoApplication,OrderController,CreateOrderRequest,DemoErrorCode}.java
│       ├── main/resources/application.yaml
│       └── test/java/com/acme/demo/OrderControllerIT.java
└── docs/decisions/{0000-use-madr.md,0001-record-stack-and-layout.md}
```

---

## Task 1: Git repo + Gradle wrapper + .gitignore

**Files:**
- Create: `.gitignore`
- Create: `gradle/wrapper/gradle-wrapper.properties` (+ wrapper jar/scripts via gradle)

- [ ] **Step 1: Initialize git (repo is not yet a git repo)**

Run:
```bash
cd /Users/npden4ik/Projects/java-boilerplate
git init
```
Expected: `Initialized empty Git repository`.

- [ ] **Step 2: Create `.gitignore`**

Create `.gitignore`:
```gitignore
.gradle/
build/
!gradle/wrapper/gradle-wrapper.jar
.idea/
*.iml
.DS_Store
out/
bin/
```

- [ ] **Step 3: Generate the Gradle 8.14 wrapper**

Run (requires a system `gradle`; if absent, install via `brew install gradle` or SDKMAN):
```bash
gradle wrapper --gradle-version 8.14
```
Expected: creates `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`.

- [ ] **Step 4: Verify the wrapper runs**

Run: `./gradlew --version`
Expected: prints `Gradle 8.14`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "chore: init repo with gradle 8.14 wrapper and gitignore"
```

---

## Task 2: Version catalog

**Files:**
- Create: `gradle/libs.versions.toml`

- [ ] **Step 1: Create the catalog**

Create `gradle/libs.versions.toml`:
```toml
[versions]
java = "21"
spring-boot = "3.5.6"

[libraries]
spring-boot-dependencies = { module = "org.springframework.boot:spring-boot-dependencies", version.ref = "spring-boot" }
spring-boot-autoconfigure = { module = "org.springframework.boot:spring-boot-autoconfigure" }
spring-boot-configuration-processor = { module = "org.springframework.boot:spring-boot-configuration-processor" }
spring-boot-autoconfigure-processor = { module = "org.springframework.boot:spring-boot-autoconfigure-processor" }
spring-boot-starter = { module = "org.springframework.boot:spring-boot-starter" }
spring-boot-starter-web = { module = "org.springframework.boot:spring-boot-starter-web" }
spring-boot-starter-validation = { module = "org.springframework.boot:spring-boot-starter-validation" }
spring-boot-starter-test = { module = "org.springframework.boot:spring-boot-starter-test" }

[plugins]
spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
```

- [ ] **Step 2: Commit**

```bash
git add gradle/libs.versions.toml
git commit -m "build: add version catalog (spring boot 3.5.6)"
```

> Note: library aliases with `version.ref` only on the BOM/processors — runtime libs get their versions from the `acme-bom` / `spring-boot-dependencies` platform applied per-module. Plugin alias `libs.plugins.spring.boot` is used by the example service.

---

## Task 3: build-logic included build + `acme.java-conventions` plugin

**Files:**
- Create: `build-logic/settings.gradle.kts`
- Create: `build-logic/build.gradle.kts`
- Create: `build-logic/src/main/kotlin/acme.java-conventions.gradle.kts`

- [ ] **Step 1: build-logic settings**

Create `build-logic/settings.gradle.kts`:
```kotlin
dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
rootProject.name = "build-logic"
```

- [ ] **Step 2: build-logic build script (kotlin-dsl + Spotless plugin on the classpath)**

Create `build-logic/build.gradle.kts`:
```kotlin
plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // Spotless plugin made available to precompiled convention scripts.
    // Adoption-time: bump to the latest from the Gradle Plugin Portal.
    implementation("com.diffplug.spotless:spotless-plugin-gradle:7.0.2")
}
```

- [ ] **Step 3: The Java conventions plugin**

Create `build-logic/src/main/kotlin/acme.java-conventions.gradle.kts`:
```kotlin
plugins {
    `java-library`
    id("com.diffplug.spotless")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters") // required by Spring
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

spotless {
    java {
        palantirJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
```

- [ ] **Step 4: Commit (build-logic compiles once wired into the root build in Task 4)**

```bash
git add build-logic
git commit -m "build: add build-logic with acme.java-conventions plugin"
```

---

## Task 4: Root settings + foojay toolchain resolver

**Files:**
- Create: `settings.gradle.kts`

- [ ] **Step 1: Root settings**

Create `settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Auto-provisions the Java 21 toolchain.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.PREFER_SETTINGS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "acme-boilerplate"

includeBuild("build-logic")

include(
    "platform:acme-bom",
    "starters:acme-web-spring-boot-autoconfigure",
    "starters:acme-web-spring-boot-starter",
    "examples:demo-service",
)
```

- [ ] **Step 2: Create empty module dirs so Gradle can configure them**

Run:
```bash
mkdir -p platform/acme-bom \
  starters/acme-web-spring-boot-autoconfigure/src/main/java/com/acme/web/error \
  starters/acme-web-spring-boot-autoconfigure/src/main/java/com/acme/web/autoconfigure \
  starters/acme-web-spring-boot-autoconfigure/src/main/resources/META-INF/spring \
  starters/acme-web-spring-boot-autoconfigure/src/test/java/com/acme/web/error \
  starters/acme-web-spring-boot-starter \
  examples/demo-service/src/main/java/com/acme/demo \
  examples/demo-service/src/main/resources \
  examples/demo-service/src/test/java/com/acme/demo
```

> The build won't succeed until each included module has a `build.gradle.kts` (Tasks 5–9). That is expected.

- [ ] **Step 3: Commit**

```bash
git add settings.gradle.kts
git commit -m "build: root settings, foojay toolchain resolver, module includes"
```

---

## Task 5: `acme-bom` (java-platform)

**Files:**
- Create: `platform/acme-bom/build.gradle.kts`

- [ ] **Step 1: BOM build script**

Create `platform/acme-bom/build.gradle.kts`:
```kotlin
plugins {
    `java-platform`
}

javaPlatform {
    allowDependencies() // required to import another BOM
}

dependencies {
    // Re-export Spring Boot's managed versions to consumers of acme-bom.
    api(platform(libs.spring.boot.dependencies))

    // Own constraints (own modules' versions) go here as the project grows, e.g.:
    // constraints { api("com.acme:acme-web-spring-boot-starter:0.1.0") }
}
```

- [ ] **Step 2: Verify it configures**

Run: `./gradlew :platform:acme-bom:help -q`
Expected: completes with no error (the `acme.bom` project resolves the catalog `libs`).

- [ ] **Step 3: Commit**

```bash
git add platform/acme-bom/build.gradle.kts
git commit -m "build: add acme-bom java-platform re-exporting spring-boot-dependencies"
```

---

## Task 6: `acme-web` error model + handler (TDD)

**Files:**
- Create: `starters/acme-web-spring-boot-autoconfigure/build.gradle.kts`
- Create: `.../src/main/java/com/acme/web/error/ErrorCode.java`
- Create: `.../src/main/java/com/acme/web/error/ApiException.java`
- Create: `.../src/main/java/com/acme/web/error/ProblemExceptionHandler.java`
- Test: `.../src/test/java/com/acme/web/error/ProblemExceptionHandlerTest.java`

- [ ] **Step 1: autoconfigure module build script**

Create `starters/acme-web-spring-boot-autoconfigure/build.gradle.kts`:
```kotlin
plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))

    api(libs.spring.boot.autoconfigure)
    // Web + validation are optional at runtime for the autoconfigure module —
    // guarded by @ConditionalOnClass; the -starter module brings them as real deps.
    compileOnly(libs.spring.boot.starter.web)
    compileOnly(libs.spring.boot.starter.validation)

    annotationProcessor(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)

    testImplementation(libs.spring.boot.starter.web)
    testImplementation(libs.spring.boot.starter.validation)
    testImplementation(libs.spring.boot.starter.test)
}
```

- [ ] **Step 2: Define the error contract (`ErrorCode`)**

Create `starters/acme-web-spring-boot-autoconfigure/src/main/java/com/acme/web/error/ErrorCode.java`:
```java
package com.acme.web.error;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error contract. Implemented per-service (usually as an enum).
 * The {@code code} is locale-independent and is what clients branch on — never the detail text.
 */
public interface ErrorCode {

    /** Stable UPPER_SNAKE identifier, e.g. {@code ORDER_NOT_FOUND}. */
    String code();

    /** HTTP status this error maps to. */
    HttpStatus status();

    /** Human-readable default title (locale-independent fallback). */
    String defaultTitle();
}
```

- [ ] **Step 3: Define `ApiException`**

Create `starters/acme-web-spring-boot-autoconfigure/src/main/java/com/acme/web/error/ApiException.java`:
```java
package com.acme.web.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Application exception carrying a stable {@link ErrorCode} and locale-independent params. */
public class ApiException extends RuntimeException {

    private final transient ErrorCode errorCode;
    private final transient Map<String, Object> params;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, Map.of(), null);
    }

    public ApiException(ErrorCode errorCode, Map<String, Object> params) {
        this(errorCode, params, null);
    }

    public ApiException(ErrorCode errorCode, Map<String, Object> params, Throwable cause) {
        super(errorCode.defaultTitle(), cause);
        this.errorCode = errorCode;
        this.params = params == null ? Map.of() : Map.copyOf(params);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public HttpStatus status() {
        return errorCode.status();
    }

    public Map<String, Object> params() {
        return params;
    }
}
```

- [ ] **Step 4: Write the failing test for the handler**

Create `starters/acme-web-spring-boot-autoconfigure/src/test/java/com/acme/web/error/ProblemExceptionHandlerTest.java`:
```java
package com.acme.web.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class ProblemExceptionHandlerTest {

    enum TestError implements ErrorCode {
        ORDER_NOT_FOUND;

        @Override
        public String code() {
            return name();
        }

        @Override
        public HttpStatus status() {
            return HttpStatus.NOT_FOUND;
        }

        @Override
        public String defaultTitle() {
            return "Order not found";
        }
    }

    @Test
    void mapsApiExceptionToProblemDetail() {
        var handler = new ProblemExceptionHandler();
        var ex = new ApiException(TestError.ORDER_NOT_FOUND, Map.of("orderId", "42"));

        ProblemDetail pd = handler.handleApiException(ex);

        assertThat(pd.getStatus()).isEqualTo(404);
        assertThat(pd.getTitle()).isEqualTo("Order not found");
        assertThat(pd.getProperties()).containsEntry("code", "ORDER_NOT_FOUND");
        assertThat(pd.getProperties()).containsKey("params");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) pd.getProperties().get("params");
        assertThat(params).containsEntry("orderId", "42");
    }
}
```

- [ ] **Step 5: Run the test — verify it fails to compile (handler not yet written)**

Run: `./gradlew :starters:acme-web-spring-boot-autoconfigure:test --tests "*ProblemExceptionHandlerTest" `
Expected: FAIL — compilation error, `ProblemExceptionHandler` does not exist.

- [ ] **Step 6: Implement `ProblemExceptionHandler`**

Create `starters/acme-web-spring-boot-autoconfigure/src/main/java/com/acme/web/error/ProblemExceptionHandler.java`:
```java
package com.acme.web.error;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders application errors as RFC 9457 problem+json. Owns the built-in Spring MVC exceptions
 * (by extending {@link ResponseEntityExceptionHandler}) and is ordered highest-precedence so it
 * wins over Boot's default handler.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProblemExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
        pd.setTitle(ex.errorCode().defaultTitle());
        pd.setType(URI.create("https://errors.acme.com/"
                + ex.errorCode().code().toLowerCase().replace('_', '-')));
        pd.setProperty("code", ex.errorCode().code());
        pd.setProperty("params", ex.params());
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            pd.setProperty("traceId", traceId);
        }
        return pd;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemDetail pd = ex.getBody();
        pd.setProperty("code", "VALIDATION_FAILED");
        List<Map<String, Object>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.<String, Object>of(
                        "field", fe.getField(),
                        "rejectedValue", String.valueOf(fe.getRejectedValue()),
                        "message", Objects.requireNonNullElse(fe.getDefaultMessage(), "invalid")))
                .toList();
        pd.setProperty("errors", errors);
        return handleExceptionInternal(ex, pd, headers, status, request);
    }
}
```

- [ ] **Step 7: Run the test — verify it passes**

Run: `./gradlew :starters:acme-web-spring-boot-autoconfigure:test --tests "*ProblemExceptionHandlerTest"`
Expected: PASS.

- [ ] **Step 8: Apply formatting and commit**

```bash
./gradlew :starters:acme-web-spring-boot-autoconfigure:spotlessApply
git add starters/acme-web-spring-boot-autoconfigure
git commit -m "feat(acme-web): RFC 9457 problem+json error model and handler"
```

---

## Task 7: `acme-web` auto-configuration registration

**Files:**
- Create: `.../src/main/java/com/acme/web/autoconfigure/AcmeWebAutoConfiguration.java`
- Create: `.../src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

- [ ] **Step 1: Auto-configuration class**

Create `starters/acme-web-spring-boot-autoconfigure/src/main/java/com/acme/web/autoconfigure/AcmeWebAutoConfiguration.java`:
```java
package com.acme.web.autoconfigure;

import com.acme.web.error.ProblemExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;

/** Registers the {@link ProblemExceptionHandler} when on a servlet web app, unless overridden. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnProperty(prefix = "acme.web.problem", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AcmeWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ProblemExceptionHandler acmeProblemExceptionHandler() {
        return new ProblemExceptionHandler();
    }
}
```

- [ ] **Step 2: Register it for auto-configuration discovery**

Create `starters/acme-web-spring-boot-autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
```
com.acme.web.autoconfigure.AcmeWebAutoConfiguration
```
> Exact path and filename matter — a typo silently disables auto-configuration.

- [ ] **Step 3: Compile the module**

Run: `./gradlew :starters:acme-web-spring-boot-autoconfigure:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Apply formatting and commit**

```bash
./gradlew :starters:acme-web-spring-boot-autoconfigure:spotlessApply
git add starters/acme-web-spring-boot-autoconfigure
git commit -m "feat(acme-web): auto-configuration registration for problem handler"
```

---

## Task 8: `acme-web` thin starter module

**Files:**
- Create: `starters/acme-web-spring-boot-starter/build.gradle.kts`

- [ ] **Step 1: Starter build script (no code — pulls autoconfigure + real runtime deps)**

Create `starters/acme-web-spring-boot-starter/build.gradle.kts`:
```kotlin
plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(project(":starters:acme-web-spring-boot-autoconfigure"))
    api(libs.spring.boot.starter)
    api(libs.spring.boot.starter.web)
    api(libs.spring.boot.starter.validation)
}
```

- [ ] **Step 2: Verify it assembles**

Run: `./gradlew :starters:acme-web-spring-boot-starter:assemble`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add starters/acme-web-spring-boot-starter/build.gradle.kts
git commit -m "feat(acme-web): thin starter aggregating autoconfigure + web + validation"
```

---

## Task 9: `demo-service` example consuming the starter

**Files:**
- Create: `examples/demo-service/build.gradle.kts`
- Create: `.../src/main/java/com/acme/demo/DemoApplication.java`
- Create: `.../src/main/java/com/acme/demo/DemoErrorCode.java`
- Create: `.../src/main/java/com/acme/demo/CreateOrderRequest.java`
- Create: `.../src/main/java/com/acme/demo/OrderController.java`
- Create: `.../src/main/resources/application.yaml`

- [ ] **Step 1: Example build script (applies Spring Boot plugin, depends on the starter)**

Create `examples/demo-service/build.gradle.kts`:
```kotlin
plugins {
    id("acme.java-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(project(":platform:acme-bom")))
    implementation(project(":starters:acme-web-spring-boot-starter"))
    testImplementation(libs.spring.boot.starter.test)
}
```

- [ ] **Step 2: Application entry point**

Create `examples/demo-service/src/main/java/com/acme/demo/DemoApplication.java`:
```java
package com.acme.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
```

- [ ] **Step 3: Service-defined error codes**

Create `examples/demo-service/src/main/java/com/acme/demo/DemoErrorCode.java`:
```java
package com.acme.demo;

import com.acme.web.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum DemoErrorCode implements ErrorCode {
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "Order not found");

    private final HttpStatus status;
    private final String defaultTitle;

    DemoErrorCode(HttpStatus status, String defaultTitle) {
        this.status = status;
        this.defaultTitle = defaultTitle;
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
        return defaultTitle;
    }
}
```

- [ ] **Step 4: Request DTO with validation**

Create `examples/demo-service/src/main/java/com/acme/demo/CreateOrderRequest.java`:
```java
package com.acme.demo;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateOrderRequest(@NotBlank String sku, @Min(1) int quantity) {}
```

- [ ] **Step 5: Controller exercising both paths**

Create `examples/demo-service/src/main/java/com/acme/demo/OrderController.java`:
```java
package com.acme.demo;

import com.acme.web.error.ApiException;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/orders")
public class OrderController {

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> create(@Valid @RequestBody CreateOrderRequest req) {
        return Map.of("status", "accepted", "sku", req.sku(), "quantity", req.quantity());
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        throw new ApiException(DemoErrorCode.ORDER_NOT_FOUND, Map.of("orderId", id));
    }
}
```

- [ ] **Step 6: Minimal application config**

Create `examples/demo-service/src/main/resources/application.yaml`:
```yaml
spring:
  application:
    name: demo-service
server:
  port: 8080
```

- [ ] **Step 7: Verify the app compiles and boots context**

Run: `./gradlew :examples:demo-service:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Apply formatting and commit**

```bash
./gradlew :examples:demo-service:spotlessApply
git add examples/demo-service
git commit -m "feat(demo): example service consuming acme-web starter"
```

---

## Task 10: Integration test — proves the starter auto-configures end-to-end (TDD)

**Files:**
- Test: `examples/demo-service/src/test/java/com/acme/demo/OrderControllerIT.java`

- [ ] **Step 1: Write the integration test**

Create `examples/demo-service/src/test/java/com/acme/demo/OrderControllerIT.java`:
```java
package com.acme.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class OrderControllerIT {

    @Autowired
    MockMvc mvc;

    @Test
    void returnsProblemJsonForMissingOrder() throws Exception {
        mvc.perform(get("/v1/orders/42"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.title").value("Order not found"))
                .andExpect(jsonPath("$.params.orderId").value("42"));
    }

    @Test
    void returnsValidationProblemForBadBody() throws Exception {
        mvc.perform(post("/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"\",\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors").isArray());
    }
}
```

- [ ] **Step 2: Run the test — verify it passes (the auto-configured handler must be active)**

Run: `./gradlew :examples:demo-service:test`
Expected: PASS — both assertions green. If the handler were NOT auto-configured, the missing-order case would return Boot's default error body without a `code` property and the test would fail. Green test = starter auto-config works end-to-end.

- [ ] **Step 3: Commit**

```bash
git add examples/demo-service/src/test
git commit -m "test(demo): assert acme-web starter emits problem+json end-to-end"
```

---

## Task 11: Full build green + CI workflow

**Files:**
- Create: `.github/workflows/ci.yml`

- [ ] **Step 1: Run the whole build locally**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — all modules compile, Spotless check passes, all tests pass.
> If `spotlessCheck` fails, run `./gradlew spotlessApply` then re-run `./gradlew build`.

- [ ] **Step 2: CI workflow**

Create `.github/workflows/ci.yml`:
```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v4
      - name: Build
        run: ./gradlew build --stacktrace
```
> Action majors (`checkout@v4`, `setup-java@v4`, `setup-gradle@v4`) are known-stable; bump at adoption if newer majors are standardized. `setup-gradle` handles caching automatically — do NOT also set `cache: gradle` on `setup-java`.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: gradle build on push and pull_request"
```

---

## Task 12: ADR seed (MADR)

**Files:**
- Create: `docs/decisions/0000-use-madr.md`
- Create: `docs/decisions/0001-record-stack-and-layout.md`

- [ ] **Step 1: Record the ADR practice**

Create `docs/decisions/0000-use-madr.md`:
```markdown
---
status: accepted
date: 2026-06-17
---

# Use Markdown Any Decision Records (MADR)

## Context and Problem Statement

We need a lightweight, in-repo way to capture architectural decisions and their rationale.

## Decision Outcome

Adopt MADR 4.0.0. ADRs live in `docs/decisions/` as `NNNN-kebab-title.md` with a `status:`
front-matter field (`proposed | accepted | rejected | deprecated | superseded by NNNN`).
ADRs are immutable — supersede, never rewrite.
```

- [ ] **Step 2: Record the stack/layout decision (points to the design spec)**

Create `docs/decisions/0001-record-stack-and-layout.md`:
```markdown
---
status: accepted
date: 2026-06-17
---

# Monorepo layout, custom starters, and core stack

## Context and Problem Statement

This is a Spring Boot microservice boilerplate (analogue of go-boilerplate) built on
ready-made Spring solutions. We need to fix the build layout, packaging mechanism, and stack.

## Decision Outcome

- Gradle (Kotlin DSL) monorepo: `build-logic` convention plugins, `java-platform` `acme-bom`,
  `gradle/libs.versions.toml` catalog.
- Reusable cross-cutting concerns ship as `acme-*-spring-boot-autoconfigure` + thin
  `acme-*-spring-boot-starter` module pairs.
- Java 21, Spring Boot 3.5.x. Oracle Database = primary/reference, Postgres swappable;
  reusable layer stays DB-agnostic.
- Full rationale and per-starter detail: `docs/superpowers/specs/2026-06-17-acme-boilerplate-design.md`.
```

- [ ] **Step 3: Commit**

```bash
git add docs/decisions
git commit -m "docs: seed MADR ADRs (0000 use-madr, 0001 stack and layout)"
```

---

## Done criteria for SP-0

- `./gradlew build` is green (compile + Spotless + all tests).
- `acme-web` starter auto-configures the problem+json handler in `demo-service` with zero wiring code in the app.
- `OrderControllerIT` proves problem+json for both an `ApiException` (404, `code=ORDER_NOT_FOUND`, `params.orderId`) and a validation failure (400, `code=VALIDATION_FAILED`, `errors[]`).
- CI runs the build on push/PR.
- `acme-bom`, version catalog, and `build-logic` conventions are in place for SP-1+ to extend.

---

## Self-review notes

- **Spec coverage:** SP-0 covers spec §4 (build-logic, BOM, catalog, autoconfigure-split, starter naming) and the §5 `acme-web` problem+json + validation slice; §9 CI (Gradle build; Spotless). Deferred to later SPs by design: i18n/idempotency/rate-limit/CORS (SP-5 web extensions), persistence/observability (SP-1), messaging/outbox (SP-2), cqrs (SP-3), security (SP-4). Schema-compat CI step lands in SP-2 (no Avro yet).
- **Type consistency:** `ErrorCode.code()/status()/defaultTitle()` used identically in `ApiException`, `ProblemExceptionHandler`, `TestError`, and `DemoErrorCode`. Property keys `code`/`params`/`errors`/`traceId` consistent across handler and both tests.
- **No placeholders:** every code/command step is concrete.
- **Version caveats:** `spring-boot 3.5.6`, `spotless 7.0.2`, GitHub Action majors are concrete and known-working; flagged adoption-time bump per spec §12.
