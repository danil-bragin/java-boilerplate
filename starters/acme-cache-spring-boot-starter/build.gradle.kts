plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(project(":starters:acme-cache-spring-boot-autoconfigure"))
    api(libs.spring.boot.starter.cache)
    api(libs.caffeine)
}
