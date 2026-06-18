plugins {
    id("acme.bank-service-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(project(":platform:acme-bom")))
    implementation(project(":starters:acme-money"))
    implementation(project(":starters:acme-persistence-spring-boot-starter"))
    implementation(project(":starters:acme-outbox-spring-boot-starter"))
    implementation(project(":starters:acme-messaging-spring-boot-starter"))
    implementation(project(":starters:acme-observability-spring-boot-starter"))
    implementation(project(":starters:acme-featureflags-spring-boot-starter"))
    // Antifraud is a headless Kafka consumer with no REST controller, but the deployed-stack
    // healthcheck and the actuator readiness/liveness probes are served over HTTP. Without a servlet
    // container the actuator endpoints are never exposed on :8080, so the container can never report
    // healthy even though the consumer is up. A minimal web server backs the actuator probes.
    implementation(libs.spring.boot.starter.web)
    implementation(project(":examples:acme-bank:bank-contracts"))
    testImplementation(project(":starters:acme-test-support"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.awaitility)
}

spotless {
    java {
        targetExclude("build/generated-main-avro-java/**")
    }
}
