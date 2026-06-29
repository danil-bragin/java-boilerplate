package com.acme.ratelimit.autoconfigure;

import java.time.Duration;

/**
 * Signals that a rate-limit decision could not be made because the Redis backend failed (unreachable,
 * timeout, CAS error) <strong>and</strong> the configured policy is fail-closed
 * ({@code acme.ratelimit.redis.fail-open=false}).
 *
 * <p>Thrown from {@link RedisProxyManagerCacheResolver} at the closest point to the failed Redis op
 * (inside Bucket4j's synchronous resolver). It propagates up through Bucket4j's servlet filter and is
 * caught by {@link RedisRateLimitOutageFilter}, which translates it into a clean HTTP 503 (never a raw
 * 500). Fail-open never throws this — it returns an "allowed" result instead.
 */
public class RedisRateLimitUnavailableException extends RuntimeException {

    private final transient Duration retryAfter;

    public RedisRateLimitUnavailableException(Duration retryAfter, Throwable cause) {
        super("Rate-limit Redis backend unavailable; rejecting request (fail-closed policy)", cause);
        this.retryAfter = retryAfter;
    }

    /** Suggested {@code Retry-After} hint for the 503 response; may be {@code null}. */
    public Duration getRetryAfter() {
        return retryAfter;
    }
}
