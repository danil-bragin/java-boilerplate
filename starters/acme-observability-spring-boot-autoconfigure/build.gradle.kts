plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(libs.spring.boot.autoconfigure)
    api(libs.spring.boot.starter.jdbc)
    api(libs.shedlock.spring)
    api(libs.shedlock.provider.jdbc.template)
    compileOnly(libs.spring.boot.starter.validation)

    annotationProcessor(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.validation)
}
