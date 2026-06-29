package com.acme.ratelimit.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Fast, container-free coverage of the Redis-backend config wiring: the {@code bucket-ttl} startup
 * validation (the security-bypass guard) and the new fail-policy property binding.
 */
class RedisRateLimitConfigTest {

    private static StandardEnvironment environmentWithLongestPeriod(long time, String unit) {
        Map<String, Object> props = new HashMap<>();
        props.put("bucket4j.filters[0].rate-limits[0].bandwidths[0].capacity", "100");
        props.put("bucket4j.filters[0].rate-limits[0].bandwidths[0].time", Long.toString(time));
        props.put("bucket4j.filters[0].rate-limits[0].bandwidths[0].unit", unit);
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", props));
        return environment;
    }

    @Test
    void ttlShorterThanLongestBandwidthPeriodFailsFast() {
        StandardEnvironment environment = environmentWithLongestPeriod(1, "hours");
        assertThatThrownBy(() -> RedisRateLimitAutoConfiguration.validateBucketTtl(Duration.ofMinutes(30), environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bucket-ttl")
                .hasMessageContaining("silently reset");
    }

    @Test
    void ttlAtLeastLongestBandwidthPeriodIsAccepted() {
        StandardEnvironment environment = environmentWithLongestPeriod(1, "hours");
        assertThatCode(() -> RedisRateLimitAutoConfiguration.validateBucketTtl(Duration.ofHours(2), environment))
                .doesNotThrowAnyException();
    }

    @Test
    void nonPositiveTtlFailsFast() {
        StandardEnvironment environment = environmentWithLongestPeriod(1, "minutes");
        assertThatThrownBy(() -> RedisRateLimitAutoConfiguration.validateBucketTtl(Duration.ZERO, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void failPolicyPropertiesBindWithSafeDefaults() {
        RateLimitProperties defaults = new RateLimitProperties();
        assertThat(defaults.getRedis().isFailOpen()).isTrue();
        assertThat(defaults.getRedis().getBucketTtl()).isEqualTo(Duration.ofHours(1));

        Map<String, Object> props = new HashMap<>();
        props.put("acme.ratelimit.backend", "redis");
        props.put("acme.ratelimit.redis.fail-open", "false");
        props.put("acme.ratelimit.redis.bucket-ttl", "30m");
        props.put("acme.ratelimit.redis.fail-closed-retry-after", "10s");
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", props));

        RateLimitProperties bound = Binder.get(environment)
                .bind("acme.ratelimit", RateLimitProperties.class)
                .get();
        assertThat(bound.getBackend()).isEqualTo(RateLimitProperties.Backend.REDIS);
        assertThat(bound.getRedis().isFailOpen()).isFalse();
        assertThat(bound.getRedis().getBucketTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(bound.getRedis().getFailClosedRetryAfter()).isEqualTo(Duration.ofSeconds(10));
    }
}
