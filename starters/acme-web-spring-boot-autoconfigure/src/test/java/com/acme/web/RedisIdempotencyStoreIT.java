package com.acme.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.web.IdempotencyStore.StoredResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Verifies the Redis-backed shared idempotency store against a real Redis (Testcontainers,
 * {@code redis:7-alpine}). This is the cross-replica store: SETNX reservation semantics, completed
 * lookups, in-progress release, and TTLs.
 */
@Testcontainers
class RedisIdempotencyStoreIT {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static LettuceConnectionFactory connectionFactory;
    static StringRedisTemplate redis;

    @BeforeAll
    static void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void tearDown() {
        connectionFactory.destroy();
    }

    private RedisIdempotencyStore store() {
        return new RedisIdempotencyStore(redis, Duration.ofHours(24));
    }

    @Test
    void reserveIsTrueFirstThenFalse() {
        RedisIdempotencyStore store = store();
        String key = "reserve-once-" + System.nanoTime();

        assertThat(store.reserve(key)).isTrue();
        assertThat(store.reserve(key)).isFalse();
    }

    @Test
    void findEmptyWhileOnlyReserved() {
        RedisIdempotencyStore store = store();
        String key = "reserved-not-complete-" + System.nanoTime();

        assertThat(store.reserve(key)).isTrue();
        assertThat(store.find(key)).isEmpty();
    }

    @Test
    void findReturnsCompletedResponse() {
        RedisIdempotencyStore store = store();
        String key = "completed-" + System.nanoTime();
        StoredResponse response =
                new StoredResponse(201, "application/json", "{\"id\":\"t-1\"}".getBytes(StandardCharsets.UTF_8));

        store.reserve(key);
        store.complete(key, response);

        Optional<StoredResponse> found = store.find(key);
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(201);
        assertThat(found.get().contentType()).isEqualTo("application/json");
        assertThat(new String(found.get().body(), StandardCharsets.UTF_8)).isEqualTo("{\"id\":\"t-1\"}");
    }

    @Test
    void releaseRemovesInProgressButNotCompleted() {
        RedisIdempotencyStore store = store();

        // in-progress key: release frees it, so it can be reserved again
        String inflight = "inflight-" + System.nanoTime();
        store.reserve(inflight);
        store.release(inflight);
        assertThat(store.reserve(inflight)).isTrue();

        // completed key: release must NOT remove the stored response
        String done = "done-" + System.nanoTime();
        store.reserve(done);
        store.complete(done, new StoredResponse(200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8)));
        store.release(done);
        assertThat(store.find(done)).isPresent();
    }

    @Test
    void reservationsAndCompletionsCarryTtl() {
        RedisIdempotencyStore store = store();

        String reserved = "ttl-reserved-" + System.nanoTime();
        store.reserve(reserved);
        assertThat(redis.getExpire("idem:" + reserved)).isGreaterThan(0);

        String completed = "ttl-completed-" + System.nanoTime();
        store.reserve(completed);
        store.complete(completed, new StoredResponse(200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8)));
        assertThat(redis.getExpire("idem:" + completed)).isGreaterThan(0);
    }

    @Test
    void twoThreadsReservingSameKeyExactlyOneTrue() throws Exception {
        RedisIdempotencyStore store = store();
        String key = "race-" + System.nanoTime();

        AtomicInteger winners = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Runnable task = () -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (store.reserve(key)) {
                    winners.incrementAndGet();
                }
            };
            var f1 = pool.submit(task);
            var f2 = pool.submit(task);
            start.countDown();
            f1.get(5, TimeUnit.SECONDS);
            f2.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(winners.get()).isEqualTo(1);
    }
}
