plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(project(":starters:acme-cqrs-spring-boot-autoconfigure"))
    api(libs.pipelinr)
    api(libs.spring.boot.starter.validation)
}
