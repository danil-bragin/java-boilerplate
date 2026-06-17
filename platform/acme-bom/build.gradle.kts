plugins {
    `java-platform`
}

javaPlatform {
    allowDependencies() // required to import another BOM
}

dependencies {
    // Re-export Spring Boot's managed versions to consumers of acme-bom.
    api(platform(libs.spring.boot.dependencies))

    // Own constraints (own modules' versions) go here as the project grows, e.g.:
    // constraints { api("com.acme:acme-web-spring-boot-starter:0.1.0") }
}
