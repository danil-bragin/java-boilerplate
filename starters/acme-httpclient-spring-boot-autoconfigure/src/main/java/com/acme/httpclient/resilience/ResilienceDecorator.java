package com.acme.httpclient.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import java.util.function.Supplier;

/**
 * Wraps a (blocking) outbound HTTP call with a Resilience4j {@link CircuitBreaker} and {@link Retry}
 * resolved by instance name, reusing the registries and presets brought by {@code acme-resilience}
 * (configured under {@code resilience4j.*}). The starter's {@code readTimeout} bounds per-call latency,
 * so a programmatic {@code TimeLimiter} is unnecessary for synchronous {@code RestClient} calls; for an
 * explicit {@code @TimeLimiter} use the annotation pattern on a delegating component (see README).
 *
 * <pre>{@code
 * Product p = resilience.call("catalog", () -> catalog.product("p-1"));
 * }</pre>
 */
public class ResilienceDecorator {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final String defaultInstance;

    public ResilienceDecorator(
            CircuitBreakerRegistry circuitBreakerRegistry, RetryRegistry retryRegistry, String defaultInstance) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.retryRegistry = retryRegistry;
        this.defaultInstance = defaultInstance;
    }

    /** Decorates and executes {@code supplier} under the named Resilience4j instance. */
    public <T> T call(String instanceName, Supplier<T> supplier) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(instanceName);
        Retry retry = retryRegistry.retry(instanceName);
        // Retry on the outside, circuit breaker on the inside: each attempt records into the breaker
        // and a retried call sees the breaker's current state.
        Supplier<T> decorated =
                Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, supplier));
        return decorated.get();
    }

    /** Decorates and executes {@code supplier} under {@code acme.httpclient.resilience.default-instance}. */
    public <T> T call(Supplier<T> supplier) {
        return call(defaultInstance, supplier);
    }
}
