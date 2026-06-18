plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(project(":starters:acme-web-spring-boot-autoconfigure"))
    api(libs.spring.boot.starter)
    api(libs.spring.boot.starter.web)
    api(libs.spring.boot.starter.validation)
    api(libs.bucket4j.spring.boot.starter)
}
