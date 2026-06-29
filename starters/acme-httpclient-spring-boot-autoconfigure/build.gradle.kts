plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(libs.spring.boot.autoconfigure)
    // RestClient, HttpServiceProxyFactory and the interface-client support live in spring-web; JSON
    // (de)serialization comes from spring-boot-starter-json (Jackson). Deliberately NOT
    // spring-boot-starter-web — an outbound HTTP client must not drag an embedded servlet server
    // (Tomcat) onto consumers. RestClient's default request factory is the JDK HttpClient (spring-web).
    api(libs.spring.web)
    api(libs.spring.boot.starter.json)
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
