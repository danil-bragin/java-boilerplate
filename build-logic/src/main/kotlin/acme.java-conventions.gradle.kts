plugins {
    `java-library`
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
