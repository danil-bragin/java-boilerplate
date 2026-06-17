plugins {
    id("acme.java-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(project(":platform:acme-bom")))
    implementation(project(":starters:acme-web-spring-boot-starter"))
    testImplementation(libs.spring.boot.starter.test)
}
