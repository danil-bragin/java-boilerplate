plugins {
    id("acme.java-conventions")
}

dependencies {
    testImplementation(platform(project(":platform:acme-bom")))
    testImplementation(libs.spring.boot.starter.test) // brings JUnit5 + AssertJ
    testImplementation(libs.jqwik)
}
