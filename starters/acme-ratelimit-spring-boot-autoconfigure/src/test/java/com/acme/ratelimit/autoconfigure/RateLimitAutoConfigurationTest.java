package com.acme.ratelimit.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.giffing.bucket4j.spring.boot.starter.config.cache.SyncCacheResolver;
import javax.cache.CacheManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Regression guard for the deployment defect "APPLICATION FAILED TO START — No Bucket4j cache
 * configuration found - cache-to-use: null".
 *
 * <p>Boots a real servlet web context with {@code bucket4j.enabled=true} and a servlet filter (the
 * production configuration of the gateway/transfers services). Before the fix this context failed
 * to start because no cache backend was provided. The test asserts the context comes up, that the
 * starter-provided JCache {@link CacheManager} exists with the configured bucket cache, and that
 * Bucket4j resolved a {@link SyncCacheResolver} against it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "bucket4j.enabled=true",
            "bucket4j.filters[0].cache-name=rate-limit-buckets",
            "bucket4j.filters[0].url=/v1/.*",
            "bucket4j.filters[0].strategy=first",
            "bucket4j.filters[0].http-status-code=TOO_MANY_REQUESTS",
            "bucket4j.filters[0].rate-limits[0].cache-key=@request.remoteAddr",
            "bucket4j.filters[0].rate-limits[0].bandwidths[0].capacity=100",
            "bucket4j.filters[0].rate-limits[0].bandwidths[0].time=1",
            "bucket4j.filters[0].rate-limits[0].bandwidths[0].unit=minutes",
            "bucket4j.filters[0].rate-limits[0].bandwidths[0].refill-speed=greedy",
        })
class RateLimitAutoConfigurationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextStartsWithBucket4jCacheBackend() {
        // The context started — the failure analyzer would have aborted startup otherwise.
        CacheManager jcacheManager = context.getBean(CacheManager.class);
        assertThat(jcacheManager.getCache("rate-limit-buckets")).isNotNull();

        // Bucket4j wired its synchronous (servlet) cache resolver against our CacheManager.
        assertThat(context.getBeansOfType(SyncCacheResolver.class)).isNotEmpty();
    }

    @SpringBootApplication
    static class TestApp {}
}
