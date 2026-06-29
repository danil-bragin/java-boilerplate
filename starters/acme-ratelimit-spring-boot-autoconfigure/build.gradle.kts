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

    // Optional Redis (distributed) backend. compileOnly so default `local` users never pull Redis:
    // Bucket4j's Lettuce ProxyManager + the raw Lettuce client. Activated only when
    // acme.ratelimit.backend=redis AND these are on the consumer classpath (see RedisRateLimit...).
    compileOnly(libs.bucket4j.lettuce)
    compileOnly(libs.lettuce.core)
    compileOnly(libs.spring.boot.starter.data.redis)

    annotationProcessor(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.web)
    // Bucket4JBootProperties carries jakarta-validation constraints; binding them needs a provider.
    testImplementation(libs.spring.boot.starter.validation)
    // Redis distributed-backend IT: real Redis via Testcontainers (acme-test-support) + the
    // Lettuce ProxyManager classes under test.
    testImplementation(project(":starters:acme-test-support"))
    testImplementation(libs.bucket4j.lettuce)
    testImplementation(libs.lettuce.core)
}
