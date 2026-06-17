plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(project(":starters:acme-messaging-spring-boot-autoconfigure"))
    api(libs.spring.kafka)
}
