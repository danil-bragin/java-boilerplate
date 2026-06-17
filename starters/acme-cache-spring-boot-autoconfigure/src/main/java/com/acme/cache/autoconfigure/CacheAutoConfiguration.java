package com.acme.cache.autoconfigure;

import com.acme.cache.TwoTierCacheManager;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Enables Spring Cache backed by Caffeine with sane defaults (10-minute TTL, 10k entries).
 * Overridable — a consumer can define their own {@link CacheManager}.
 *
 * <p>When {@code acme.cache.two-tier.enabled=true} and Redis is on the classpath, a
 * {@link TwoTierCacheManager} (Caffeine L1 + Redis L2) is registered instead.
 */
@AutoConfiguration
@ConditionalOnClass({CaffeineCacheManager.class, Caffeine.class})
@EnableCaching
public class CacheAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "acme.cache.two-tier", name = "enabled", havingValue = "true")
    @ConditionalOnClass(RedisConnectionFactory.class)
    @ConditionalOnBean(RedisConnectionFactory.class)
    public CacheManager twoTierCacheManager(RedisConnectionFactory redisConnectionFactory) {
        Caffeine<Object, Object> caffeine =
                Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(10)).maximumSize(10_000);
        CaffeineCacheManager l1 = new CaffeineCacheManager();
        l1.setCaffeine(caffeine);
        RedisCacheManager l2 = RedisCacheManager.builder(redisConnectionFactory).build();
        return new TwoTierCacheManager(l1, l2);
    }

    @Bean
    @ConditionalOnMissingBean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(
                Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(10)).maximumSize(10_000));
        return manager;
    }
}
