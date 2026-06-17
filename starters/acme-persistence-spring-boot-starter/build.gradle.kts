plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(project(":starters:acme-persistence-spring-boot-autoconfigure"))
    api(libs.spring.boot.starter.data.jpa)
    api(libs.flyway.core)
    api(libs.flyway.database.postgresql)
    api(libs.flyway.database.oracle)
    api(libs.ojdbc11)
    api(libs.postgresql)
}
