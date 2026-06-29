plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(project(":starters:acme-httpclient-spring-boot-autoconfigure"))
    // Runtime deps the autoconfigure needs: RestClient + interface-client support (spring-web) and
    // the observation API. Token relay (acme-security) and resilience (acme-resilience) are optional
    // and brought by the consumer — the autoconfig backs off when they are absent.
    api(libs.spring.boot.starter.web)
    api(libs.micrometer.observation)
}
