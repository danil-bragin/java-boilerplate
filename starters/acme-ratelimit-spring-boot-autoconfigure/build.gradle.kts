plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(libs.spring.boot.autoconfigure)
    // Bucket4j servlet rate-limiting. Its JCache (JSR-107) path needs a javax.cache.CacheManager
    // bean holding the configured bucket cache(s) — this module provides one out of the box.
    api(libs.bucket4j.spring.boot.starter)
    // Caffeine-backed JSR-107 provider so Bucket4j's JCache CacheResolver resolves without a
    // separate caching product. Per-instance buckets (see RateLimitAutoConfiguration javadoc).
    api(libs.caffeine)
    api(libs.caffeine.jcache)

    annotationProcessor(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.web)
    // Bucket4JBootProperties carries jakarta-validation constraints; binding them needs a provider.
    testImplementation(libs.spring.boot.starter.validation)
}
