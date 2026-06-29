package com.acme.ratelimit.autoconfigure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Outermost servlet guard that converts a fail-closed Redis rate-limiter outage into a clean HTTP 503.
 *
 * <p>Bucket4j's servlet filter has no notion of "backend unavailable" — when the Redis CAS throws, the
 * exception would bubble out of the filter chain as a raw 500. This filter wraps the chain and catches
 * the {@link RedisRateLimitUnavailableException} that {@link RedisProxyManagerCacheResolver} raises
 * under the fail-closed policy, emitting a {@code 503 Service Unavailable} with an optional
 * {@code Retry-After} header instead.
 *
 * <p>Registered at {@link Ordered#HIGHEST_PRECEDENCE} so it sits <em>outside</em> Bucket4j's servlet
 * filter (whose default order is {@code HIGHEST_PRECEDENCE + 10}); the try/catch therefore spans the
 * rate-limit decision. Under the default fail-open policy nothing is thrown and this filter is a
 * transparent pass-through.
 */
public class RedisRateLimitOutageFilter extends OncePerRequestFilter implements Ordered {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } catch (RedisRateLimitUnavailableException ex) {
            if (response.isCommitted()) {
                throw ex;
            }
            response.reset();
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            if (ex.getRetryAfter() != null && !ex.getRetryAfter().isNegative()) {
                long seconds = Math.max(1, (long) Math.ceil(ex.getRetryAfter().toMillis() / 1000.0));
                response.setHeader("Retry-After", Long.toString(seconds));
            }
            response.setContentType("application/json");
            response.getWriter()
                    .append(
                            "{\"error\":\"rate_limiter_unavailable\",\"message\":\"Rate limiter backend is temporarily unavailable.\"}");
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
