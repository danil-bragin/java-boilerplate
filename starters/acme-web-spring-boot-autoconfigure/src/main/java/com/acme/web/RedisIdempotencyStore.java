package com.acme.web;

import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis-backed shared idempotency store for multi-instance deployments. Unlike the in-memory store,
 * this one is visible to every replica so a retried request is de-duplicated regardless of which
 * instance handled the original.
 *
 * <p>Encoding under key {@code idem:{key}}:
 *
 * <ul>
 *   <li>{@code IN_PROGRESS} — a reservation marker (the request is executing).
 *   <li>{@code DONE\n<status>\n<contentType>\n<base64 body>} — a completed response.
 * </ul>
 *
 * <p>Both states carry a TTL (default 24h) so the namespace cannot grow without bound.
 */
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final String PREFIX = "idem:";
    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String DONE_PREFIX = "DONE\n";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisIdempotencyStore(StringRedisTemplate redis) {
        this(redis, Duration.ofHours(24));
    }

    public RedisIdempotencyStore(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public Optional<StoredResponse> find(String key) {
        String value = redis.opsForValue().get(PREFIX + key);
        if (value == null || !value.startsWith(DONE_PREFIX)) {
            return Optional.empty();
        }
        return Optional.of(decode(value));
    }

    @Override
    public boolean reserve(String key) {
        // atomic SETNX + EX: only the FIRST caller across all replicas wins
        Boolean reserved = redis.opsForValue().setIfAbsent(PREFIX + key, IN_PROGRESS, ttl);
        return Boolean.TRUE.equals(reserved);
    }

    @Override
    public void complete(String key, StoredResponse response) {
        // overwrite the reservation with the serialized response, refreshing the TTL
        redis.opsForValue().set(PREFIX + key, encode(response), ttl);
    }

    @Override
    public void release(String key) {
        // Only release an in-progress reservation — never a completed response. We GET then DEL
        // guarded by the value check. There is a small race window (a concurrent complete between
        // the GET and DEL) which is acceptable for this example; a Lua CAS would close it fully.
        String value = redis.opsForValue().get(PREFIX + key);
        if (IN_PROGRESS.equals(value)) {
            redis.delete(PREFIX + key);
        }
    }

    private static String encode(StoredResponse response) {
        String contentType = response.contentType() == null ? "" : response.contentType();
        byte[] body = response.body() == null ? new byte[0] : response.body();
        return DONE_PREFIX + response.status() + "\n" + contentType + "\n"
                + Base64.getEncoder().encodeToString(body);
    }

    private static StoredResponse decode(String value) {
        // DONE\n<status>\n<contentType>\n<base64 body> — split into exactly 4 parts (body may be empty)
        String[] parts = value.split("\n", 4);
        int status = Integer.parseInt(parts[1]);
        String contentType = parts[2].isEmpty() ? null : parts[2];
        byte[] body = parts.length > 3 ? Base64.getDecoder().decode(parts[3]) : new byte[0];
        return new StoredResponse(status, contentType, body);
    }
}
