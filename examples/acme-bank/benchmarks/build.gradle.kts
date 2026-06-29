import io.gatling.gradle.GatlingRunTask

plugins {
    id("acme.java-conventions")
    alias(libs.plugins.gatling)
}

// ---------------------------------------------------------------------------------------------
// BANK-13 load-benchmark harness. A NON-Spring, on-demand tool module: it drives the live
// compose.bank.yaml stack (gateway on :8080, Keycloak on :8082) with REAL Keycloak tokens and
// measures throughput / latency percentiles / error rate per path to expose the architecture's
// known pressure points (BANK-11 source-account lock, derived-balance SUM reads, saga settle).
//
// The Gatling gradle plugin adds the `src/gatling/java` source set and the `gatlingRun` task.
// Load runs ONLY via `gatlingRun` — this module is NOT wired into `build`/`check` (see below),
// so a constrained CI without Docker still builds green; only the simulations' compilation is
// exercised by the default build, keeping the harness wiring sound.
// ---------------------------------------------------------------------------------------------

dependencies {
    // Setup helpers (token fetch, account open/fund) use Jackson + java.net.http (JDK built-in).
    gatlingImplementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")

    // acme.java-conventions adds an unversioned junit-platform-launcher to testRuntimeOnly; this
    // non-Spring module has no test source, but the platform supplies the managed version so the
    // (empty) test classpath still resolves and `gradle build` stays green.
    testRuntimeOnly(platform(project(":platform:acme-bom")))
}

gatling {
    // Keep the JVM modest: this runs co-located with five service containers + infra on one host.
    // The --add-opens is required by Gatling's StringInternals on JDK 17+ (reflective access to
    // java.lang for its zero-copy String stats writer).
    jvmArgs =
        listOf(
            "-Xms512m",
            "-Xmx1g",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
        )
}

// The benchmarks are on-demand only. `gradle build`/`check` MUST NOT run load: the gatling plugin
// does not attach `gatlingRun` to `check` by default, and we assert that here by NOT adding any
// such dependency. (Verified in Task 5: `gradle build` does not trigger a GatlingRunTask.)
tasks.withType<GatlingRunTask>().configureEach {
    // Surface the bench system properties to the forked Gatling JVM so -DBENCH_* on the gradle
    // command line reaches BenchEnv, and keep the required --add-opens (the gatling{} extension's
    // jvmArgs are NOT inherited by the task once we set jvmArgs here, so re-declare them).
    val benchProps =
        System.getProperties().stringPropertyNames().filter { it.startsWith("BENCH_") }
    jvmArgs =
        listOf(
            "-Xms512m",
            "-Xmx1g",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
        ) + benchProps.map { "-D$it=${System.getProperty(it)}" }
}
