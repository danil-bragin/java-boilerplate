plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(project(":starters:acme-httpclient-spring-boot-autoconfigure"))
    // Runtime deps: RestClient + interface-client support (spring-web) + JSON (Jackson) + the observation
    // API. No embedded server — this is an outbound client, so a consumer that isn't a web app stays one.
    // Token relay (acme-security) and resilience (acme-resilience) are optional and brought by the consumer.
    api(libs.spring.web)
    api(libs.spring.boot.starter.json)
    api(libs.micrometer.observation)
}
