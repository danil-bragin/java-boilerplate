package com.acme.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Idempotent retries of UNSAFE requests carrying an {@code Idempotency-Key}: the first request
 * reserves the key and executes; a later retry replays the stored 2xx response; a concurrent
 * request whose key is still in-progress gets 409 Conflict. Only 2xx outcomes are cached (4xx/5xx
 * release the reservation so the client can retry after fixing the request). The default store is
 * in-process with a TTL — use a shared store for multi-instance deployments.
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

        var completed = store.find(key);
        if (completed.isPresent()) {
            writeStored(response, completed.get());
            return;
        }
        if (!store.reserve(key)) {
            // either it completed between find() and reserve() — replay — or it's still in progress -> 409
            var raced = store.find(key);
            if (raced.isPresent()) {
                writeStored(response, raced.get());
            } else {
                response.setStatus(HttpServletResponse.SC_CONFLICT);
            }
            return;
        }

        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        boolean cached = false;
        try {
            chain.doFilter(request, wrapper);
            int status = wrapper.getStatus();
            if (status >= 200 && status < 300) {
                store.complete(
                        key,
                        new IdempotencyStore.StoredResponse(
                                status, wrapper.getContentType(), wrapper.getContentAsByteArray()));
                cached = true;
            }
        } finally {
            if (!cached) {
                store.release(key);
            }
            wrapper.copyBodyToResponse();
        }
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
