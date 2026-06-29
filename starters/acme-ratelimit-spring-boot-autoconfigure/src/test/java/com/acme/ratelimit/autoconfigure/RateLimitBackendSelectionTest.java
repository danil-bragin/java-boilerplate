package com.acme.ratelimit.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.giffing.bucket4j.spring.boot.starter.config.cache.SyncCacheResolver;
import com.giffing.bucket4j.spring.boot.starter.config.cache.jcache.JCacheCacheResolver;
import io.lettuce.core.RedisClient;
import javax.cache.CacheManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Fast, container-free proof of the {@code acme.ratelimit.backend} selector on the LOCAL side: the
 * in-process Caffeine/JCache backend is active by default and when {@code backend=local}, and steps
 * aside when {@code backend=redis}. (The Redis side needs a real Redis and is covered by
 * {@link RedisDistributedRateLimitIT}.)
 */
class RateLimitBackendSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RateLimitAutoConfiguration.class))
            .withPropertyValues(
                    "bucket4j.enabled=true",
                    "bucket4j.filters[0].cache-name=rate-limit-buckets",
                    "bucket4j.filters[0].url=/v1/.*");

    @Test
    void defaultBackendIsLocalInProcessJCache() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(CacheManager.class);
            assertThat(context.getBean(SyncCacheResolver.class)).isInstanceOf(JCacheCacheResolver.class);
            assertThat(context).doesNotHaveBean(RedisClient.class);
        });
    }

    @Test
    void explicitLocalBackendKeepsInProcessJCache() {
        runner.withPropertyValues("acme.ratelimit.backend=local").run(context -> {
            assertThat(context).hasSingleBean(CacheManager.class);
            assertThat(context.getBean(SyncCacheResolver.class)).isInstanceOf(JCacheCacheResolver.class);
        });
    }

    @Test
    void redisBackendDisablesTheLocalInProcessBackend() {
        // With backend=redis the local auto-configuration must not contribute its JCache backend;
        // the distributed Redis backend (a separate auto-configuration) takes over instead.
        runner.withPropertyValues("acme.ratelimit.backend=redis").run(context -> {
            assertThat(context).doesNotHaveBean(CacheManager.class);
            assertThat(context).doesNotHaveBean(SyncCacheResolver.class);
        });
    }
}
