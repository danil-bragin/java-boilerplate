package com.acme.web;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Default in-process idempotency store. For multi-instance services, override with a shared store. */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final ConcurrentMap<String, StoredResponse> store = new ConcurrentHashMap<>();

    @Override
    public Optional<StoredResponse> find(String key) {
        return Optional.ofNullable(store.get(key));
    }

    @Override
    public void save(String key, StoredResponse response) {
        store.putIfAbsent(key, response);
    }
}
