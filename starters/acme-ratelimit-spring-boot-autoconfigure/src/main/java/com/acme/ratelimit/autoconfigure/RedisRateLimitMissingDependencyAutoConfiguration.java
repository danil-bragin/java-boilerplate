package com.acme.ratelimit.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Fail-fast guard for the misconfiguration {@code acme.ratelimit.backend=redis} requested while the
 * Redis (Lettuce) classes are NOT on the classpath. Without this, the local backend has already
 * stepped aside (its {@code backend=local} condition does not match) and the Redis backend cannot
 * activate either, so Bucket4j would abort startup with the opaque "No Bucket4j cache configuration
 * found - cache-to-use: null". This turns that into a clear, actionable message naming the missing
 * dependency.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "acme.ratelimit", name = "backend", havingValue = "redis")
@ConditionalOnMissingClass({
    "io.lettuce.core.RedisClient",
    "io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager"
})
public class RedisRateLimitMissingDependencyAutoConfiguration {

    @Bean
    Object rateLimitRedisBackendDependencyCheck() {
        throw new IllegalStateException(
                "acme.ratelimit.backend=redis requires the Redis distributed backend on the classpath, "
                        + "but it is missing. Add 'org.springframework.boot:spring-boot-starter-data-redis' "
                        + "(Lettuce) and 'com.bucket4j:bucket4j_jdk17-lettuce', or set "
                        + "acme.ratelimit.backend=local to use the in-process per-replica backend.");
    }
}
