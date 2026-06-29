plugins {
    // Supply-chain: `gradle cyclonedxBom` emits a CycloneDX SBOM of the whole build's dependencies
    // (build/reports/bom.json + bom.xml), uploaded as a CI artifact.
    id("org.cyclonedx.bom") version "1.10.0"
}

// Coordinates for the aggregate root (required by the CycloneDX SBOM metadata).
group = "com.acme"
version = "0.1.0"

// Root aggregate tasks for the acme-bank deployable stack.

// Builds every bank service's executable boot jar — the per-service Dockerfiles copy these.
// Run this before `docker compose -f examples/acme-bank/compose.bank.yaml up --build`.
tasks.register("bankJars") {
    group = "acme-bank"
    description = "Builds the executable boot jars for all five bank services."
    dependsOn(
        ":examples:acme-bank:gateway:bootJar",
        ":examples:acme-bank:transfers:bootJar",
        ":examples:acme-bank:accounts:bootJar",
        ":examples:acme-bank:antifraud:bootJar",
        ":examples:acme-bank:notifications:bootJar",
    )
}
