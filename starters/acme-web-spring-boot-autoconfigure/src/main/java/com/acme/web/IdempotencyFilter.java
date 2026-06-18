package com.acme.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Replays a previously stored response when a request carries an {@code Idempotency-Key} already seen
 * (idempotent retries return the original result). Only applies to unsafe methods (POST/PATCH/PUT).
 * Responses with a 5xx status are not stored (transient failures should be retryable).
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
        if (wrapper.getStatus() < 500) {
            store.save(
                    key,
                    new IdempotencyStore.StoredResponse(
                            wrapper.getStatus(), wrapper.getContentType(), wrapper.getContentAsByteArray()));
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
