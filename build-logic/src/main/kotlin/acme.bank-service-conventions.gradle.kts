plugins {
    id("acme.java-conventions")
}

dependencies {
    // jMolecules DDD stereotypes (architecturally evident code) — versions via the jMolecules BOM.
    "implementation"(platform("org.jmolecules:jmolecules-bom:2025.0.2"))
    "implementation"("org.jmolecules:jmolecules-ddd")
    "implementation"("org.jmolecules:jmolecules-events")

    // Architecture fitness functions.
    "testImplementation"(platform("org.jmolecules:jmolecules-bom:2025.0.2"))
    "testImplementation"("com.tngtech.archunit:archunit-junit5:1.4.2")
    "testImplementation"("org.jmolecules.integrations:jmolecules-archunit")
}

// Container-heavy integration tests: each bank service IT spins a Postgres + Redpanda via Testcontainers.
// Spring caches one ApplicationContext (and its live containers) per distinct test config, so within a
// single test JVM the container sets ACCUMULATE across classes. On a small CI runner (GitHub ubuntu-latest:
// 2 vCPU / ~7 GB) that exhausts RAM, and a later context (e.g. SagaReconcilerIT) then fails to launch
// Redpanda — its cached context-load failure cascading to every test in the class. Restart the worker JVM
// after each test class so Testcontainers reaps that class's containers at JVM exit, keeping at most one
// container set alive at a time; cap the test heap to leave the box's RAM for Docker. Fast local machines
// are unaffected by the correctness change (only a modest wall-clock cost from not reusing contexts).
tasks.withType<Test>().configureEach {
    setForkEvery(1L)
    maxParallelForks = 1
    maxHeapSize = "1g"
}

// When the Spring Boot plugin is applied, disable the thin "-plain" jar so build/libs holds exactly
// one artifact (the executable boot jar). The per-service Dockerfiles copy build/libs/*.jar, so an
// extra plain jar would make that glob ambiguous.
plugins.withId("org.springframework.boot") {
    tasks.named<Jar>("jar") { enabled = false }
}
