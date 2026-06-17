plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(project(":starters:acme-observability-spring-boot-autoconfigure"))
    api(libs.spring.boot.starter.validation)
    api(libs.spring.boot.starter.actuator)
    api(libs.micrometer.tracing.bridge.otel)
    api(libs.opentelemetry.exporter.otlp)
    api(libs.micrometer.registry.otlp)
    api(libs.micrometer.registry.prometheus)
}
