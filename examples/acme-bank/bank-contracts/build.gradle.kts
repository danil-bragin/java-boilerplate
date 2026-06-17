plugins {
    id("acme.java-conventions")
    alias(libs.plugins.avro)
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(project(":starters:acme-money"))
    api(project(":starters:acme-avro-spring-boot-starter")) // Avro + Confluent serde for consumers
    testImplementation(libs.spring.boot.starter.test)
}

// Generated Avro sources are not subject to Spotless style.
spotless {
    java {
        targetExclude("build/generated-main-avro-java/**")
    }
}
