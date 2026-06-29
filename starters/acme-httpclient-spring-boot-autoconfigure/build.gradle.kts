plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(libs.spring.boot.autoconfigure)
    // RestClient, HttpServiceProxyFactory and the interface-client support live in spring-web,
    // pulled (with Jackson) by the web starter.
    api(libs.spring.boot.starter.web)
    // ObservationRegistry — outbound calls become observations (tracing + metrics) when a registry bean exists.
    api(libs.micrometer.observation)

    // Token relay reaches into the resource-server JWT types; gated @ConditionalOnClass so it stays optional.
    compileOnly(libs.spring.boot.starter.oauth2.resource.server)
    // Resilience4j decorator is gated @ConditionalOnClass; the consumer brings it via acme-resilience.
    compileOnly(libs.resilience4j.spring.boot3)

    annotationProcessor(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.oauth2.resource.server)
    testImplementation(libs.resilience4j.spring.boot3)
}
