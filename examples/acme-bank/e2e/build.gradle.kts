plugins {
    id("acme.java-conventions")
}

// Test-only module: it has NO production code. It brings up the whole compose.bank.yaml stack via
// Testcontainers and drives the system through the gateway's public HTTP edge with a real Keycloak
// token. It is heavy (image pulls + image builds), so it is EXCLUDED from the default build: the
// `test` task drops the `e2e` tag, and a dedicated `e2eTest` task runs it on demand.

dependencies {
    testImplementation(platform(project(":platform:acme-bom")))

    // Testcontainers + ComposeContainer (versions managed by the Spring Boot BOM via acme-bom).
    testImplementation("org.testcontainers:testcontainers")
    testImplementation(libs.testcontainers.junit.jupiter)

    // JUnit 5, Awaitility, Jackson, AssertJ (all BOM-managed).
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(libs.awaitility)
    testImplementation("com.fasterxml.jackson.core:jackson-databind")
    testImplementation("org.assertj:assertj-core")
}

// The default `test` task (run by `check`/`build`) MUST NOT trigger the e2e: exclude the tag so a
// constrained environment without the Keycloak/otel-lgtm images still builds green. Only the e2e
// classes' compilation is exercised by the default build, proving the wiring stays sound.
tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("e2e") }
}

// On-demand full-stack run. ComposeContainer.withBuild(true) builds the service images from the
// BANK-9 Dockerfiles, which need each service's bootJar present — hence the dependsOn.
val e2eTest =
    tasks.register<Test>("e2eTest") {
        description = "Full-stack e2e (requires Docker + image pulls/builds)."
        group = "verification"
        useJUnitPlatform { includeTags("e2e") }
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        shouldRunAfter(tasks.named("test"))
        dependsOn(
            ":examples:acme-bank:gateway:bootJar",
            ":examples:acme-bank:transfers:bootJar",
            ":examples:acme-bank:accounts:bootJar",
            ":examples:acme-bank:antifraud:bootJar",
            ":examples:acme-bank:notifications:bootJar",
        )
    }
