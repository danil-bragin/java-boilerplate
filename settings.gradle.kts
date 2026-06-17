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
    "examples:demo-service",
)
