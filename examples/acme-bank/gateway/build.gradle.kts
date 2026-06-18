plugins {
    id("acme.bank-service-conventions")
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.openapi.generator)
}

dependencies {
    implementation(platform(project(":platform:acme-bom")))
    implementation(project(":starters:acme-money"))
    implementation(project(":starters:acme-persistence-spring-boot-starter"))
    implementation(project(":starters:acme-messaging-spring-boot-starter"))
    implementation(project(":starters:acme-web-spring-boot-starter"))
    implementation(project(":starters:acme-ratelimit-spring-boot-starter"))
    implementation(project(":starters:acme-security-spring-boot-starter"))
    implementation(project(":starters:acme-observability-spring-boot-starter"))
    implementation(project(":examples:acme-bank:bank-contracts"))
    // Shared idempotency across gateway replicas: when SPRING_DATA_REDIS_HOST is set (compose),
    // acme-web's RedisIdempotencyStore activates. Local/test default (no Redis) → in-memory store.
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.resilience4j.spring.boot3)
    implementation(libs.springdoc.openapi.starter.webmvc.ui)
    // Generated-interface support (openapi-generator spring, interfaceOnly).
    implementation(libs.swagger.annotations)
    implementation(libs.jackson.databind.nullable)
    runtimeOnly(libs.postgresql)
    testImplementation(project(":starters:acme-test-support"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.awaitility)
}

openApiGenerate {
    generatorName.set("spring")
    inputSpec.set("$projectDir/src/main/resources/openapi/bank-api.yaml")
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.absolutePath)
    apiPackage.set("com.acme.bank.gateway.api")
    modelPackage.set("com.acme.bank.gateway.api.dto")
    configOptions.set(
        mapOf(
            "interfaceOnly" to "true",
            "useSpringBoot3" to "true",
            "useTags" to "true",
            "documentationProvider" to "none",
            "openApiNullable" to "false",
            "skipDefaultInterface" to "false",
        ),
    )
}

sourceSets.main {
    java.srcDir(layout.buildDirectory.dir("generated/openapi/src/main/java"))
}

tasks.named("compileJava") { dependsOn("openApiGenerate") }
tasks.named("spotlessJava") { dependsOn("openApiGenerate") }

spotless {
    java {
        targetExclude("build/generated/**")
    }
}
