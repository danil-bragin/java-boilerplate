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
    "starters:acme-test-support",
    "starters:acme-web-spring-boot-autoconfigure",
    "starters:acme-web-spring-boot-starter",
    "starters:acme-persistence-spring-boot-autoconfigure",
    "starters:acme-persistence-spring-boot-starter",
    "starters:acme-observability-spring-boot-autoconfigure",
    "starters:acme-observability-spring-boot-starter",
    "starters:acme-cqrs-spring-boot-autoconfigure",
    "starters:acme-cqrs-spring-boot-starter",
    "starters:acme-outbox-spring-boot-starter",
    "starters:acme-security-spring-boot-autoconfigure",
    "starters:acme-security-spring-boot-starter",
    "starters:acme-cache-spring-boot-autoconfigure",
    "starters:acme-cache-spring-boot-starter",
    "starters:acme-resilience-spring-boot-starter",
    "examples:demo-service",
)
