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
