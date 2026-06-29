package com.acme.ratelimit.autoconfigure;

import com.giffing.bucket4j.spring.boot.starter.config.cache.AbstractCacheResolverTemplate;
import com.giffing.bucket4j.spring.boot.starter.config.cache.ProxyManagerWrapper;
import com.giffing.bucket4j.spring.boot.starter.config.cache.SyncCacheResolver;
import com.giffing.bucket4j.spring.boot.starter.context.RateLimitResult;
import com.giffing.bucket4j.spring.boot.starter.context.RateLimitResultWrapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.AbstractProxyManager;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Bucket4j {@link SyncCacheResolver} backed by a distributed {@link AbstractProxyManager} (a Redis
 * {@code LettuceBasedProxyManager}). Bucket4j's <strong>servlet</strong> filter resolves its buckets
 * through a {@code SyncCacheResolver}; the stock {@code LettuceCacheResolver} the giffing starter
 * ships is an {@code AsyncCacheResolver} (used only by the reactive WebFlux/Gateway filters), so it
 * is invisible to the servlet filter. This resolver bridges the gap: it drives the same distributed
 * Lettuce ProxyManager through Bucket4j's <em>synchronous</em> API so the servlet filter enforces a
 * cluster-wide limit.
 *
 * <p><strong>Redis-outage fail policy.</strong> The synchronous Redis CAS that backs each decision can
 * throw when Redis is unreachable/slow. Left unhandled, that exception bubbles through Bucket4j's
 * servlet filter to the caller as a raw HTTP 500 — a rate-limiter outage becoming a self-inflicted
 * outage of the whole API. This resolver intercepts at the closest reachable point: it overrides
 * {@link #resolve(String)} and wraps the {@link ProxyManagerWrapper} the
 * {@link AbstractCacheResolverTemplate} builds, so every Redis exception thrown while building the
 * bucket or executing {@code tryConsumeAndReturnRemaining} is caught and an explicit, documented
 * policy applied:
 *
 * <ul>
 *   <li><strong>fail-open</strong> (default): return an "allowed" result so the request passes; emit a
 *       WARN log and increment the {@code acme.ratelimit.redis.errors} counter ({@code outcome=allowed}).
 *   <li><strong>fail-closed</strong>: throw {@link RedisRateLimitUnavailableException}, which
 *       {@link RedisRateLimitOutageFilter} turns into a clean HTTP 503; also logged and metered
 *       ({@code outcome=rejected}).
 * </ul>
 *
 * <p>This covers the servlet (synchronous) path only — the one the Redis backend wires. It does NOT
 * change the legitimate "limit exceeded" outcome (which never throws) nor the local in-process backend.
 *
 * <p>All rate-limit filters share a single Redis keyspace keyed by the per-caller rate-limit key
 * (e.g. {@code @request.remoteAddr}); this mirrors the giffing {@code LettuceCacheResolver}'s own
 * single-keyspace behavior. The {@code cacheName} argument selects only the ProxyManager (one here),
 * not the key.
 */
public class RedisProxyManagerCacheResolver extends AbstractCacheResolverTemplate<byte[]> implements SyncCacheResolver {

    private static final Logger log = LoggerFactory.getLogger(RedisProxyManagerCacheResolver.class);

    private final AbstractProxyManager<byte[]> proxyManager;
    private final boolean failOpen;
    private final Duration failClosedRetryAfter;
    private final Runnable errorCounter;

    /**
     * @param proxyManager the distributed Redis ProxyManager backing every bucket
     * @param failOpen {@code true} to allow requests through on a Redis failure, {@code false} to
     *     reject with HTTP 503
     * @param failClosedRetryAfter {@code Retry-After} hint carried on the fail-closed 503 (nullable)
     * @param errorCounter invoked once per Redis failure to record the outage metric; never
     *     {@code null} (pass a no-op when no {@code MeterRegistry} is present)
     */
    public RedisProxyManagerCacheResolver(
            AbstractProxyManager<byte[]> proxyManager,
            boolean failOpen,
            Duration failClosedRetryAfter,
            Runnable errorCounter) {
        this.proxyManager = proxyManager;
        this.failOpen = failOpen;
        this.failClosedRetryAfter = failClosedRetryAfter;
        this.errorCounter = errorCounter;
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

    /**
     * Wraps the template-built {@link ProxyManagerWrapper} so a Redis failure during the rate-limit
     * decision is caught and the configured fail policy applied, instead of propagating as a raw 500.
     */
    @Override
    public ProxyManagerWrapper resolve(String cacheName) {
        ProxyManagerWrapper delegate = super.resolve(cacheName);
        return (key, numTokens, isEstimation, bucketConfiguration, metricBucketListener, configVersion, strategy) -> {
            try {
                return delegate.tryConsumeAndReturnRemaining(
                        key,
                        numTokens,
                        isEstimation,
                        bucketConfiguration,
                        metricBucketListener,
                        configVersion,
                        strategy);
            } catch (RuntimeException ex) {
                return onRedisFailure(ex, numTokens, isEstimation, bucketConfiguration);
            }
        };
    }

    private RateLimitResultWrapper onRedisFailure(
            RuntimeException ex, Integer numTokens, boolean isEstimation, BucketConfiguration bucketConfiguration) {
        errorCounter.run();
        if (failOpen) {
            log.warn(
                    "Redis rate-limit backend failed; FAIL-OPEN policy allowing the request through "
                            + "(limit NOT enforced for this request). Cause: {}: {}",
                    ex.getClass().getName(),
                    ex.getMessage());
            return new RateLimitResultWrapper(RateLimitResult.builder()
                    .estimation(isEstimation)
                    .consumed(true)
                    .remainingTokens(estimateRemaining(bucketConfiguration, numTokens))
                    .nanosToWaitForRefill(0)
                    .nanosToWaitForReset(0)
                    .build());
        }
        log.warn(
                "Redis rate-limit backend failed; FAIL-CLOSED policy rejecting the request with HTTP 503. Cause: {}: {}",
                ex.getClass().getName(),
                ex.getMessage());
        throw new RedisRateLimitUnavailableException(failClosedRetryAfter, ex);
    }

    /**
     * Best-effort {@code X-Rate-Limit-Remaining} value for a fail-open response: the smallest bandwidth
     * capacity minus the tokens this request would have taken. The real count is unknown (the backend
     * is down), so this is purely cosmetic for the response header and never under-reports below zero.
     */
    private static long estimateRemaining(BucketConfiguration bucketConfiguration, Integer numTokens) {
        try {
            long minCapacity = Long.MAX_VALUE;
            for (Bandwidth bandwidth : bucketConfiguration.getBandwidths()) {
                minCapacity = Math.min(minCapacity, bandwidth.getCapacity());
            }
            if (minCapacity == Long.MAX_VALUE) {
                return 0;
            }
            return Math.max(0, minCapacity - (numTokens == null ? 0 : numTokens));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }
}
