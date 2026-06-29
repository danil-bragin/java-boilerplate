package com.acme.ratelimit.autoconfigure;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

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
@Validated
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
         * keeps the Redis keyspace bounded for per-caller (e.g. per-IP) limiting.
         *
         * <p><strong>SECURITY-CRITICAL:</strong> this MUST be {@code >=} the longest configured
         * Bucket4j bandwidth period. If a key is evicted before its window closes, the bucket state
         * is lost and the limit silently <em>resets</em> mid-window — a caller can then exceed the
         * intended ceiling. {@link RedisRateLimitAutoConfiguration} validates this at startup against
         * the configured {@code bucket4j.filters[].rate-limits[].bandwidths[]} and fails fast if the
         * ttl is too small. Defaults to one hour.
         */
        @NotNull
        private Duration bucketTtl = Duration.ofHours(1);

        /**
         * Fail policy when the Redis call backing a rate-limit decision throws (Redis unreachable,
         * timeout, CAS failure). A rate-limiter outage must have an <em>explicit</em>, documented
         * behaviour rather than surfacing as a raw HTTP 500.
         *
         * <ul>
         *   <li><strong>{@code true} (default — fail-open):</strong> allow the request through so a
         *       Redis outage cannot take down the whole API. The outage is made visible via a WARN
         *       log and the {@code acme.ratelimit.redis.errors} Micrometer counter
         *       ({@code outcome=allowed}). Trade-off: during the outage the rate limit is not
         *       enforced.
         *   <li><strong>{@code false} (fail-closed):</strong> reject with a clean HTTP 503
         *       (Service Unavailable) plus a {@code Retry-After} header — never a raw 500 — also
         *       logged and metered ({@code outcome=rejected}). Trade-off: a Redis outage rejects
         *       traffic on the rate-limited paths.
         * </ul>
         */
        private boolean failOpen = true;

        /**
         * {@code Retry-After} hint (seconds, rounded up) sent on the fail-closed HTTP 503. Only used
         * when {@link #isFailOpen()} is {@code false}. Defaults to 5 seconds.
         */
        @NotNull
        private Duration failClosedRetryAfter = Duration.ofSeconds(5);

        public Duration getBucketTtl() {
            return bucketTtl;
        }

        public void setBucketTtl(Duration bucketTtl) {
            this.bucketTtl = bucketTtl;
        }

        public boolean isFailOpen() {
            return failOpen;
        }

        public void setFailOpen(boolean failOpen) {
            this.failOpen = failOpen;
        }

        public Duration getFailClosedRetryAfter() {
            return failClosedRetryAfter;
        }

        public void setFailClosedRetryAfter(Duration failClosedRetryAfter) {
            this.failClosedRetryAfter = failClosedRetryAfter;
        }
    }
}
