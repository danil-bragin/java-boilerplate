package com.acme.ratelimit.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.giffing.bucket4j.spring.boot.starter.config.cache.SyncCacheResolver;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.AbstractProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.cache.CacheManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration test for the DISTRIBUTED rate-limit guarantee — the whole reason the Redis backend
 * exists. Boots the auto-configuration with {@code acme.ratelimit.backend=redis} against a real
 * Redis (Testcontainers), then proves the property that the in-process {@code local} backend cannot
 * give: TWO independently-built ProxyManagers (two simulated replicas) pointing at the SAME Redis
 * share ONE limit. With a limit of 5, across both "replicas" exactly 5 requests pass and the 6th is
 * throttled — NOT 10 (which is what per-replica in-process buckets would allow).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "acme.ratelimit.backend=redis",
            "bucket4j.enabled=true",
            "bucket4j.filters[0].cache-name=rate-limit-buckets",
            "bucket4j.filters[0].url=/v1/.*",
            "bucket4j.filters[0].strategy=first",
            "bucket4j.filters[0].http-status-code=TOO_MANY_REQUESTS",
            "bucket4j.filters[0].rate-limits[0].cache-key=@request.remoteAddr",
            "bucket4j.filters[0].rate-limits[0].bandwidths[0].capacity=5",
            "bucket4j.filters[0].rate-limits[0].bandwidths[0].time=1",
            "bucket4j.filters[0].rate-limits[0].bandwidths[0].unit=minutes",
            "bucket4j.filters[0].rate-limits[0].bandwidths[0].refill-speed=greedy",
        })
@Testcontainers
class RedisDistributedRateLimitIT {

    private static final int LIMIT = 5;

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private ApplicationContext context;

    /** Replica 1: the ProxyManager the auto-configuration wired from the Redis backend. */
    @Autowired
    private AbstractProxyManager<byte[]> replicaOneProxyManager;

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    @Test
    void redisBackendIsSelectedAndLocalBackendIsOff() {
        // The Redis backend supplied the servlet cache resolver...
        assertThat(context.getBean(SyncCacheResolver.class)).isInstanceOf(RedisProxyManagerCacheResolver.class);
        assertThat(context.getBean(RedisClient.class)).isNotNull();
        // ...and the in-process local backend stepped aside (no JCache CacheManager).
        assertThat(context.getBeanNamesForType(CacheManager.class)).isEmpty();
    }

    @Test
    void twoReplicasSharingOneRedisEnforceOneGlobalLimit() {
        // Replica 2: an independent client + ProxyManager, built exactly like production wiring,
        // pointing at the SAME Redis as replica 1.
        RedisClient replicaTwoClient = RedisClient.create(
                RedisURI.builder().withHost(redisHost).withPort(redisPort).build());
        try {
            LettuceBasedProxyManager<byte[]> replicaTwoProxyManager =
                    RedisRateLimitAutoConfiguration.lettuceProxyManager(replicaTwoClient, Duration.ofHours(1));

            BucketConfiguration config = BucketConfiguration.builder()
                    .addLimit(Bandwidth.builder()
                            .capacity(LIMIT)
                            .refillGreedy(LIMIT, Duration.ofHours(1))
                            .build())
                    .build();

            // Same caller key on both replicas -> the SAME Redis-backed bucket.
            byte[] key = "caller-198.51.100.7".getBytes(StandardCharsets.UTF_8);
            BucketProxy bucketOnReplicaOne = replicaOneProxyManager.builder().build(key, () -> config);
            BucketProxy bucketOnReplicaTwo = replicaTwoProxyManager.builder().build(key, () -> config);

            int passed = 0;
            // Fire LIMIT + 1 requests, alternating replicas. If buckets were per-replica (local
            // backend) all LIMIT+1 would pass; sharing Redis, only LIMIT may pass.
            for (int i = 0; i < LIMIT + 1; i++) {
                BucketProxy bucket = (i % 2 == 0) ? bucketOnReplicaOne : bucketOnReplicaTwo;
                if (bucket.tryConsume(1)) {
                    passed++;
                }
            }

            assertThat(passed)
                    .as("two replicas sharing one Redis must enforce a single global limit of %d", LIMIT)
                    .isEqualTo(LIMIT);
            // And the shared bucket is now exhausted from either replica's view.
            assertThat(bucketOnReplicaOne.tryConsume(1)).isFalse();
            assertThat(bucketOnReplicaTwo.tryConsume(1)).isFalse();
        } finally {
            replicaTwoClient.shutdown();
        }
    }

    @SpringBootApplication
    static class TestApp {}
}
