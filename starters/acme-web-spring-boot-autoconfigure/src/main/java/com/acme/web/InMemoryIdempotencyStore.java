package com.acme.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;

/**
 * In-process idempotency store with a TTL (bounded growth) and a reserve-on-first marker so
 * concurrent first-requests for the same key cannot both execute. Override with a shared (e.g.
 * Redis) store for multi-instance deployments.
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private enum State {
        IN_PROGRESS,
        COMPLETED
    }

    private record Entry(State state, StoredResponse response) {}

    private final Cache<String, Entry> cache;

    public InMemoryIdempotencyStore() {
        this(Duration.ofHours(24));
    }

    public InMemoryIdempotencyStore(Duration ttl) {
        this.cache =
                Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(100_000).build();
    }

    @Override
    public Optional<StoredResponse> find(String key) {
        Entry entry = cache.getIfPresent(key);
        return entry != null && entry.state() == State.COMPLETED ? Optional.of(entry.response()) : Optional.empty();
    }

    @Override
    public boolean reserve(String key) {
        // atomic put-if-absent: only the first caller succeeds
        return cache.asMap().putIfAbsent(key, new Entry(State.IN_PROGRESS, null)) == null;
    }

    @Override
    public void complete(String key, StoredResponse response) {
        cache.put(key, new Entry(State.COMPLETED, response));
    }

    @Override
    public void release(String key) {
        cache.asMap().computeIfPresent(key, (k, e) -> e.state() == State.IN_PROGRESS ? null : e);
    }
}
