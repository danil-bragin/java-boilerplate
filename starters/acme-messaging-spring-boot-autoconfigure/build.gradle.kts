plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(libs.spring.boot.autoconfigure)
    api(libs.spring.boot.starter.jdbc)
    api(libs.spring.kafka)
    // Optional: a Micrometer counter on DLT routing (acme.saga.dlt). Optional so consumers without
    // an actuator/metrics stack still get the error handler; the counter is a no-op then.
    compileOnly(libs.micrometer.core)

    annotationProcessor(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.micrometer.core)
}
