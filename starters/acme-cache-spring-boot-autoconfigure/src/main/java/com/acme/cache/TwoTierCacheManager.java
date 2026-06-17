package com.acme.cache;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/** Composes an L1 and L2 {@link CacheManager} into {@link TwoTierCache}s. */
public class TwoTierCacheManager implements CacheManager {

    private final CacheManager l1;
    private final CacheManager l2;

    public TwoTierCacheManager(CacheManager l1, CacheManager l2) {
        this.l1 = l1;
        this.l2 = l2;
    }

    @Override
    public Cache getCache(String name) {
        Cache l1Cache = l1.getCache(name);
        Cache l2Cache = l2.getCache(name);
        if (l1Cache == null || l2Cache == null) {
            return l1Cache != null ? l1Cache : l2Cache;
        }
        return new TwoTierCache(name, l1Cache, l2Cache);
    }

    @Override
    public Collection<String> getCacheNames() {
        Set<String> names = new LinkedHashSet<>(l1.getCacheNames());
        names.addAll(l2.getCacheNames());
        return names;
    }
}
