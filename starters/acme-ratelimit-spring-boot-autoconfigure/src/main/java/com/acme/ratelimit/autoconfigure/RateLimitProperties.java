package com.acme.ratelimit.autoconfigure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the rate-limit bucket backend.
 *
 * <p>The {@link #getBackend() backend} selector chooses where Bucket4j stores its buckets:
 *
 * <ul>
 *   <li>{@link Backend#LOCAL} (default) — a Caffeine-backed JSR-107 cache, in-process. Buckets are
 *       <strong>per replica</strong>: each instance keeps its own counters, so the effective limit
 *       multiplies with the number of replicas. Unchanged legacy behavior.
 *   <li>{@link Backend#REDIS} — a Bucket4j distributed {@code ProxyManager} backed by Redis
 *       (Lettuce). Buckets are <strong>shared across replicas</strong>, so a per-caller limit is
 *       enforced cluster-wide. Requires {@code bucket4j_jdk17-lettuce} + Lettuce on the classpath
 *       (typically via {@code spring-boot-starter-data-redis}).
 * </ul>
 */
@ConfigurationProperties("acme.ratelimit")
public class RateLimitProperties {

    /** Where Bucket4j stores rate-limit buckets. */
    public enum Backend {
        /** In-process Caffeine JSR-107 cache. Per-replica counters (default). */
        LOCAL,
        /** Distributed Redis (Lettuce) ProxyManager. Cluster-wide shared counters. */
        REDIS
    }

    /** Selected bucket backend. Defaults to {@link Backend#LOCAL} to preserve legacy behavior. */
    private Backend backend = Backend.LOCAL;

    private final Redis redis = new Redis();

    public Backend getBackend() {
        return backend;
    }

    public void setBackend(Backend backend) {
        this.backend = backend;
    }

    public Redis getRedis() {
        return redis;
    }

    /** Redis-backend tuning (only consulted when {@code backend=redis}). */
    public static class Redis {

        /**
         * Time-to-live applied to each bucket key in Redis, expressed as the maximum time the bucket
         * needs to refill to full. Keys are evicted once a bucket would be fully replenished, which
         * keeps the Redis keyspace bounded for per-caller (e.g. per-IP) limiting. MUST be {@code >=}
         * the longest configured bandwidth period, otherwise bucket state is evicted before the
         * window closes and the limit silently resets. Defaults to one hour.
         */
        private Duration bucketTtl = Duration.ofHours(1);

        public Duration getBucketTtl() {
            return bucketTtl;
        }

        public void setBucketTtl(Duration bucketTtl) {
            this.bucketTtl = bucketTtl;
        }
    }
}
