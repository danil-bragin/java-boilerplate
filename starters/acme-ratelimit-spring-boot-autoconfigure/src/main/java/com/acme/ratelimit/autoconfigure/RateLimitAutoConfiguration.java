package com.acme.ratelimit.autoconfigure;

import com.giffing.bucket4j.spring.boot.starter.config.cache.Bucket4jCacheConfiguration;
import com.giffing.bucket4j.spring.boot.starter.config.cache.SyncCacheResolver;
import com.giffing.bucket4j.spring.boot.starter.config.cache.jcache.JCacheCacheResolver;
import com.giffing.bucket4j.spring.boot.starter.context.properties.Bucket4JBootProperties;
import com.giffing.bucket4j.spring.boot.starter.context.properties.Bucket4JConfiguration;
import com.github.benmanes.caffeine.jcache.spi.CaffeineCachingProvider;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.configuration.MutableConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Provides a working Bucket4j cache backend so any service that enables rate-limiting
 * ({@code bucket4j.enabled=true}) BOOTS without per-service cache wiring.
 *
 * <p>Bucket4j's servlet (synchronous) filter path resolves its buckets through the JSR-107
 * (JCache) {@code CacheResolver}, which requires a {@link javax.cache.CacheManager} bean that
 * already contains the cache(s) named by {@code bucket4j.filters[].cache-name}. With no such
 * bean Bucket4j finds no cache backend and the context fails to start with
 * {@code "No Bucket4j cache configuration found - cache-to-use: null"} — the defect this
 * auto-configuration fixes.
 *
 * <p>The backend is a Caffeine-backed JSR-107 provider. Buckets are therefore <strong>per
 * replica</strong>: each instance keeps its own counters, so a per-caller limit multiplies with the
 * replica count. This is the {@code local} (default) backend. For buckets SHARED across
 * horizontally-scaled replicas — a cluster-wide limit — set {@code acme.ratelimit.backend=redis};
 * {@link RedisRateLimitAutoConfiguration} then provides a distributed Redis ProxyManager and this
 * default steps aside (its {@code backend=local} condition no longer matches).
 *
 * <p>Runs only when rate-limiting is enabled and {@code acme.ratelimit.backend} is {@code local} or
 * unset. Guarded with {@link ConditionalOnMissingBean} so a consumer-defined
 * {@link javax.cache.CacheManager} wins.
 */
@AutoConfiguration(before = Bucket4jCacheConfiguration.class)
@ConditionalOnClass({Caching.class, CaffeineCachingProvider.class})
@ConditionalOnProperty(prefix = "bucket4j", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "acme.ratelimit", name = "backend", havingValue = "local", matchIfMissing = true)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitAutoConfiguration {

    /**
     * A JSR-107 {@link javax.cache.CacheManager} pre-populated with every cache named by the
     * configured Bucket4j filters (plus the filter-config cache when that feature is on). Values
     * are {@code byte[]} — Bucket4j stores serialized bucket state.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(CacheManager.class)
    public CacheManager rateLimitJCacheManager(Environment environment) {
        CacheManager cacheManager = Caching.getCachingProvider(CaffeineCachingProvider.class.getName())
                .getCacheManager();
        for (String cacheName : resolveCacheNames(environment)) {
            if (cacheManager.getCache(cacheName) == null) {
                cacheManager.createCache(cacheName, caffeineBucketCacheConfig());
            }
        }
        return cacheManager;
    }

    /**
     * Bucket4j's synchronous (servlet filter) {@link SyncCacheResolver}, backed by the JCache
     * {@link CacheManager} above. Provided explicitly — rather than relying on Bucket4j's own
     * {@code @ConditionalOnBean(CacheManager.class)} JCache wiring — so the resolver is guaranteed
     * present regardless of auto-configuration ordering. With a resolver bean present, Bucket4j's
     * startup check passes and the servlet filter is created.
     */
    @Bean
    @ConditionalOnMissingBean(SyncCacheResolver.class)
    public SyncCacheResolver bucket4jSyncCacheResolver(CacheManager rateLimitJCacheManager) {
        return new JCacheCacheResolver(rateLimitJCacheManager);
    }

    private static MutableConfiguration<String, byte[]> caffeineBucketCacheConfig() {
        MutableConfiguration<String, byte[]> configuration =
                new MutableConfiguration<String, byte[]>().setTypes(String.class, byte[].class);
        // A Caffeine eviction policy keeps the bucket store bounded for per-key rate limiting
        // (e.g. one bucket per remote address). Caffeine reads these hints via the JCache SPI.
        configuration.setManagementEnabled(false).setStatisticsEnabled(false);
        return configuration;
    }

    private static Set<String> resolveCacheNames(Environment environment) {
        Set<String> names = new LinkedHashSet<>();
        Bucket4JBootProperties properties = Binder.get(environment)
                .bind("bucket4j", Bucket4JBootProperties.class)
                .orElseGet(Bucket4JBootProperties::new);
        for (Bucket4JConfiguration filter : properties.getFilters()) {
            if (filter.getCacheName() != null) {
                names.add(filter.getCacheName());
            }
        }
        if (properties.isFilterConfigCachingEnabled() && properties.getFilterConfigCacheName() != null) {
            names.add(properties.getFilterConfigCacheName());
        }
        // Bucket4j's default cache name when a filter omits one — always have it available.
        names.add("buckets");
        return names;
    }
}
