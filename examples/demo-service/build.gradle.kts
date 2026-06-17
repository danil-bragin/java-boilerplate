plugins {
    id("acme.java-conventions")
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.avro)
}

spotless {
    java {
        // Exclude Avro-generated sources from style checks.
        targetExclude("build/generated-main-avro-java/**/*.java", "build/generated-test-avro-java/**/*.java")
    }
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootBuildImage>("bootBuildImage") {
    imageName.set("acme/demo-service:${project.version}")
    environment.set(mapOf("BP_JVM_VERSION" to "21", "BP_JVM_CDS_ENABLED" to "true"))
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
    implementation(project(":starters:acme-messaging-spring-boot-starter"))
    implementation(project(":starters:acme-avro-spring-boot-starter"))
    testImplementation(project(":starters:acme-test-support"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
}
