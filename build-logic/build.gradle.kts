plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // Spotless plugin made available to precompiled convention scripts.
    implementation("com.diffplug.spotless:spotless-plugin-gradle:7.0.2")
}
