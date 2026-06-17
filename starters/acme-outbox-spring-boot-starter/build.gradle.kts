plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(libs.spring.modulith.starter.jpa)
    api(libs.spring.modulith.events.jackson)
    api(libs.spring.modulith.events.kafka) // pulls spring-kafka transitively
}
