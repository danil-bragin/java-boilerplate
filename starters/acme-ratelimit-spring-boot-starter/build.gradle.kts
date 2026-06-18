plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(project(":starters:acme-ratelimit-spring-boot-autoconfigure"))
    api(libs.bucket4j.spring.boot.starter)
}
