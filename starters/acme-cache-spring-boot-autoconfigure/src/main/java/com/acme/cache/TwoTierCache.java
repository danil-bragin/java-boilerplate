package com.acme.cache;

import java.util.concurrent.Callable;
import org.springframework.cache.Cache;

/**
 * Two-tier cache: L1 (in-process, e.g. Caffeine) in front of L2 (shared, e.g. Redis). Reads check
 * L1 then L2 (populating L1 on an L2 hit); writes and evictions apply to both tiers.
 *
 * <p>Note: cross-instance L1 invalidation (Redis pub/sub) is a future enhancement — an evict on
 * one node clears its own L1 + the shared L2, but other nodes' L1 entries expire by TTL.
 */
public class TwoTierCache implements Cache {

    private final String name;
    private final Cache l1;
    private final Cache l2;

    public TwoTierCache(String name, Cache l1, Cache l2) {
        this.name = name;
        this.l1 = l1;
        this.l2 = l2;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return this;
    }

    @Override
    public ValueWrapper get(Object key) {
        ValueWrapper hit = l1.get(key);
        if (hit != null) {
            return hit;
        }
        ValueWrapper l2hit = l2.get(key);
        if (l2hit != null) {
            l1.put(key, l2hit.get());
        }
        return l2hit;
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        ValueWrapper wrapper = get(key);
        return wrapper == null ? null : type.cast(wrapper.get());
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        ValueWrapper wrapper = get(key);
        if (wrapper != null) {
            @SuppressWarnings("unchecked")
            T existing = (T) wrapper.get();
            return existing;
        }
        try {
            T value = valueLoader.call();
            put(key, value);
            return value;
        } catch (Exception e) {
            throw new ValueRetrievalException(key, valueLoader, e);
        }
    }

    @Override
    public void put(Object key, Object value) {
        l2.put(key, value);
        l1.put(key, value);
    }

    @Override
    public void evict(Object key) {
        l2.evict(key);
        l1.evict(key);
    }

    @Override
    public void clear() {
        l2.clear();
        l1.clear();
    }
}
