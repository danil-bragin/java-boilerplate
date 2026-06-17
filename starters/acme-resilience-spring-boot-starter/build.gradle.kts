plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(libs.resilience4j.spring.boot3)
    api(libs.spring.boot.starter.aop)
}
