package com.acme.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Replays a previously stored response when a request carries an {@code Idempotency-Key} already seen,
 * guarding against SEQUENTIAL retries of requests whose outcome was successful (2xx). Only applies to
 * unsafe methods (POST/PATCH/PUT).
 *
 * <p>Only 2xx responses are cached: 4xx responses (e.g. validation failures) are intentionally NOT
 * stored so that a client which fixes its request body can retry under the same key and succeed.
 * 5xx responses are similarly not cached as the server-side failure may be transient.
 *
 * <p><b>Known limitation:</b> the in-memory store does not prevent concurrent first-requests from
 * executing simultaneously. The idempotency guarantee covers only sequential retries — a concurrent
 * duplicate that arrives before the first response is committed will proceed to execution. For
 * distributed or concurrent-safe idempotency use a shared, atomic store (e.g. Redis with a
 * compare-and-set).
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final String HEADER = "Idempotency-Key";

    private final IdempotencyStore store;

    public IdempotencyFilter(IdempotencyStore store) {
        this.store = store;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key == null || !isUnsafe(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        var existing = store.find(key);
        if (existing.isPresent()) {
            writeStored(response, existing.get());
            return;
        }

        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        chain.doFilter(request, wrapper);
        int status = wrapper.getStatus();
        if (status >= 200 && status < 300) {
            store.save(
                    key,
                    new IdempotencyStore.StoredResponse(
                            status, wrapper.getContentType(), wrapper.getContentAsByteArray()));
        }
        wrapper.copyBodyToResponse();
    }

    private void writeStored(HttpServletResponse response, IdempotencyStore.StoredResponse stored) throws IOException {
        response.setStatus(stored.status());
        if (stored.contentType() != null) {
            response.setContentType(stored.contentType());
        }
        response.getOutputStream().write(stored.body());
    }

    private boolean isUnsafe(String method) {
        return "POST".equals(method) || "PATCH".equals(method) || "PUT".equals(method);
    }
}
