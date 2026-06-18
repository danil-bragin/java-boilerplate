plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))

    api(libs.spring.boot.autoconfigure)
    api(libs.caffeine)
    // Web + validation are optional at runtime for the autoconfigure module —
    // guarded by @ConditionalOnClass; the -starter module brings them as real deps.
    compileOnly(libs.spring.boot.starter.web)
    compileOnly(libs.spring.boot.starter.validation)

    annotationProcessor(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)

    testImplementation(libs.spring.boot.starter.web)
    testImplementation(libs.spring.boot.starter.validation)
    testImplementation(libs.spring.boot.starter.test)
}
