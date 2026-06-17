plugins {
    id("acme.java-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(project(":platform:acme-bom")))
    implementation(project(":starters:acme-web-spring-boot-starter"))
    implementation(project(":starters:acme-persistence-spring-boot-starter"))
    implementation(project(":starters:acme-observability-spring-boot-starter"))
    implementation(project(":starters:acme-cqrs-spring-boot-starter"))
    implementation(project(":starters:acme-outbox-spring-boot-starter"))
    implementation(project(":starters:acme-security-spring-boot-starter"))
    implementation(project(":starters:acme-cache-spring-boot-starter"))
    implementation(project(":starters:acme-resilience-spring-boot-starter"))
    implementation(project(":starters:acme-featureflags-spring-boot-starter"))
    testImplementation(project(":starters:acme-test-support"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
}
