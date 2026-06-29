package com.acme.ratelimit.autoconfigure;

import com.giffing.bucket4j.spring.boot.starter.config.cache.AbstractCacheResolverTemplate;
import com.giffing.bucket4j.spring.boot.starter.config.cache.SyncCacheResolver;
import io.github.bucket4j.distributed.proxy.AbstractProxyManager;
import java.nio.charset.StandardCharsets;

/**
 * A Bucket4j {@link SyncCacheResolver} backed by a distributed {@link AbstractProxyManager} (a Redis
 * {@code LettuceBasedProxyManager}). Bucket4j's <strong>servlet</strong> filter resolves its buckets
 * through a {@code SyncCacheResolver}; the stock {@code LettuceCacheResolver} the giffing starter
 * ships is an {@code AsyncCacheResolver} (used only by the reactive WebFlux/Gateway filters), so it
 * is invisible to the servlet filter. This resolver bridges the gap: it drives the same distributed
 * Lettuce ProxyManager through Bucket4j's <em>synchronous</em> API so the servlet filter enforces a
 * cluster-wide limit.
 *
 * <p>All rate-limit filters share a single Redis keyspace keyed by the per-caller rate-limit key
 * (e.g. {@code @request.remoteAddr}); this mirrors the giffing {@code LettuceCacheResolver}'s own
 * single-keyspace behavior. The {@code cacheName} argument selects only the ProxyManager (one here),
 * not the key.
 */
public class RedisProxyManagerCacheResolver extends AbstractCacheResolverTemplate<byte[]> implements SyncCacheResolver {

    private final AbstractProxyManager<byte[]> proxyManager;

    public RedisProxyManagerCacheResolver(AbstractProxyManager<byte[]> proxyManager) {
        this.proxyManager = proxyManager;
    }

    /** Synchronous: the servlet filter blocks on each Redis CAS round-trip. */
    @Override
    public boolean isAsync() {
        return false;
    }

    @Override
    public byte[] castStringToCacheKey(String key) {
        return key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public AbstractProxyManager<byte[]> getProxyManager(String cacheName) {
        return proxyManager;
    }
}
