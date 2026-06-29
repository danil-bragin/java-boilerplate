plugins {
    id("acme.java-conventions")
    alias(libs.plugins.avro)
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(project(":starters:acme-money"))
    api(project(":starters:acme-avro-spring-boot-starter")) // Avro + Confluent serde for consumers
    api(libs.spring.boot.autoconfigure) // MoneyJacksonAutoConfiguration (shared Money JSON serde)
    api("com.fasterxml.jackson.core:jackson-databind") // MoneyJacksonModule (version from acme-bom)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    testImplementation(libs.spring.boot.starter.test)
    // spring-web brings Jackson2ObjectMapperBuilder so JacksonAutoConfiguration builds a real
    // ObjectMapper in the test (the bank services are web apps and have it) — lets the test prove the
    // Money module is actually folded into the application ObjectMapper, not just registered as a bean.
    testImplementation("org.springframework:spring-web")
}

// Generated Avro sources are not subject to Spotless style.
spotless {
    java {
        targetExclude("build/generated-main-avro-java/**")
    }
}
