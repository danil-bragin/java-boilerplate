plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(libs.spring.boot.testcontainers)
    api(libs.testcontainers.postgresql)
    api(libs.testcontainers.junit.jupiter)
    api(libs.spring.boot.starter.test)
    api(libs.testcontainers.redpanda)
    api(libs.spring.kafka)
}
