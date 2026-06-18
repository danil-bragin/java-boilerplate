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

// When the Spring Boot plugin is applied, disable the thin "-plain" jar so build/libs holds exactly
// one artifact (the executable boot jar). The per-service Dockerfiles copy build/libs/*.jar, so an
// extra plain jar would make that glob ambiguous.
plugins.withId("org.springframework.boot") {
    tasks.named<Jar>("jar") { enabled = false }
}
