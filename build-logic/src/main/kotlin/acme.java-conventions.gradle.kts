plugins {
    `java-library`
    jacoco
    `maven-publish`
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

// Publishing — only the reusable `:starters:*` modules are published (consumable as dependencies);
// the `:examples:*` apps are not. Targets GitHub Packages; credentials come from the CI GITHUB_TOKEN
// (or local `gpr.user`/`gpr.key` gradle properties). Source + javadoc jars are attached so consumers
// get IDE support. A `mavenLocal` install also works for trying a starter without a remote push.
if (path.startsWith(":starters:")) {
    group = "com.acme"
    version = (findProperty("acmeVersion") as String?) ?: "0.1.0"

    java {
        withSourcesJar()
        withJavadocJar()
    }

    publishing {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
            }
        }
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/danil-bragin/java-boilerplate")
                credentials {
                    username = System.getenv("GITHUB_ACTOR") ?: (findProperty("gpr.user") as String?)
                    password = System.getenv("GITHUB_TOKEN") ?: (findProperty("gpr.key") as String?)
                }
            }
        }
    }
}
