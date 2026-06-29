plugins {
    `java-library`
    jacoco
    id("com.diffplug.spotless")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters") // required by Spring
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// Coverage: every module emits an XML report (consumed by the CI coverage summary/badge) after its tests.
tasks.named<JacocoReport>("jacocoTestReport") {
    reports {
        xml.required = true
        html.required = true
        csv.required = true
    }
}
tasks.named<Test>("test") {
    finalizedBy(tasks.named("jacocoTestReport"))
}

dependencies {
    // JUnit Platform Launcher is required on the test runtime classpath with JUnit 5.12+/Gradle 8.x.
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

spotless {
    java {
        palantirJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
