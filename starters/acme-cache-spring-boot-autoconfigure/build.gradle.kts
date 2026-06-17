plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(libs.spring.boot.autoconfigure)
    api(libs.spring.boot.starter.cache)
    api(libs.caffeine)
    compileOnly(libs.spring.boot.starter.data.redis)
    testImplementation(libs.spring.boot.starter.data.redis)

    annotationProcessor(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
}
