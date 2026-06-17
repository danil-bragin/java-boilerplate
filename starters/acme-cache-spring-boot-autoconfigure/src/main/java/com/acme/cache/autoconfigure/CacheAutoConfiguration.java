package com.acme.cache.autoconfigure;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;

/**
 * Enables Spring Cache backed by Caffeine with sane defaults (10-minute TTL, 10k entries).
 * Overridable — a consumer can define their own {@link CacheManager}.
 */
@AutoConfiguration
@ConditionalOnClass({CaffeineCacheManager.class, Caffeine.class})
@EnableCaching
public class CacheAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(
                Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(10)).maximumSize(10_000));
        return manager;
    }
}
