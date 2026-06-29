package com.acme.ratelimit.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.TimeoutOptions;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Failure-path test for the DEFAULT fail-open policy. Boots the Redis backend against a real Redis,
 * then stops Redis mid-stream and proves that a rate-limited request is still ALLOWED (the limiter
 * outage does not take down the API) and that the {@code acme.ratelimit.redis.errors} counter is
 * incremented so the outage is observable.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "acme.ratelimit.backend=redis",
            // fail-open is the default; left unset on purpose to prove the default behaviour.
            "bucket4j.enabled=true",
            "bucket4j.filters[0].cache-name=rate-limit-buckets",
            "bucket4j.filters[0].url=/v1/.*",
            "bucket4j.filters[0].strategy=first",
            "bucket4j.filters[0].http-status-code=TOO_MANY_REQUESTS",
            "bucket4j.filters[0].rate-limits[0].cache-key=getRemoteAddr()",
            "bucket4j.filters[0].rate-limits[0].bandwidths[0].capacity=1000",
            "bucket4j.filters[0].rate-limits[0].bandwidths[0].time=1",
            "bucket4j.filters[0].rate-limits[0].bandwidths[0].unit=minutes",
            "bucket4j.filters[0].rate-limits[0].bandwidths[0].refill-speed=greedy",
        })
@Testcontainers
class RedisRateLimitFailOpenIT {

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void redisOutageWithDefaultPolicyAllowsRequestAndMetersTheError() {
        // Redis is up: the rate-limited endpoint responds normally.
        assertThat(rest.getForEntity("/v1/ping", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Bring Redis down mid-stream.
        REDIS.stop();

        // Fail-open: the request is still allowed through (no raw 500, no 503).
        ResponseEntity<String> afterOutage = rest.getForEntity("/v1/ping", String.class);
        assertThat(afterOutage.getStatusCode()).isEqualTo(HttpStatus.OK);

        // ...and the outage is visible on the metric.
        double errors = meterRegistry
                .get(RedisRateLimitAutoConfiguration.REDIS_ERRORS_METRIC)
                .tag("outcome", "allowed")
                .counter()
                .count();
        assertThat(errors).isGreaterThanOrEqualTo(1.0);
    }

    @SpringBootApplication
    static class TestApp {

        /** A MeterRegistry so the optional {@code acme.ratelimit.redis.errors} counter is recorded. */
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        /**
         * Consumer-supplied {@link RedisClient} (exercises the "bring your own client" override path)
         * tuned to fail fast once Redis drops, so the outage surfaces deterministically and quickly.
         */
        @Bean(destroyMethod = "shutdown")
        RedisClient rateLimitRedisClient(Environment environment) {
            RedisURI uri = RedisURI.builder()
                    .withHost(environment.getProperty("spring.data.redis.host"))
                    .withPort(Integer.parseInt(environment.getProperty("spring.data.redis.port")))
                    .build();
            RedisClient client = RedisClient.create(uri);
            client.setOptions(ClientOptions.builder()
                    .timeoutOptions(TimeoutOptions.enabled(Duration.ofSeconds(2)))
                    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                    .build());
            return client;
        }

        @RestController
        static class PingController {
            @GetMapping("/v1/ping")
            String ping() {
                return "pong";
            }
        }
    }
}
