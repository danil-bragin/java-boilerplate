plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(project(":starters:acme-featureflags-spring-boot-autoconfigure"))
    api(libs.openfeature.sdk)
}
