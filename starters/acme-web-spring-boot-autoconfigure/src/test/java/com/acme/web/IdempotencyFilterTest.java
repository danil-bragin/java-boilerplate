package com.acme.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class IdempotencyFilterTest {

    @Test
    void replaysStoredResponseForRepeatedKey() throws Exception {
        IdempotencyFilter filter = new IdempotencyFilter(new InMemoryIdempotencyStore());
        AtomicInteger handlerInvocations = new AtomicInteger();

        // a chain that writes a 201 body and counts invocations
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws java.io.IOException {
                handlerInvocations.incrementAndGet();
                HttpServletResponse http = (HttpServletResponse) res;
                http.setStatus(201);
                http.setContentType("application/json");
                http.getWriter().write("{\"id\":\"t-1\"}");
            }
        };

        MockHttpServletRequest first = post("key-1");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(first, firstResponse, chain);

        MockHttpServletRequest second = post("key-1");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, new MockFilterChain()); // chain not invoked on replay

        assertThat(handlerInvocations.get()).isEqualTo(1); // handler ran once
        assertThat(secondResponse.getStatus()).isEqualTo(201);
        assertThat(secondResponse.getContentAsString()).isEqualTo("{\"id\":\"t-1\"}");
    }

    @Test
    void doesNotCacheFourXxSoSubsequentRequestWithSameKeyReExecutes() throws Exception {
        IdempotencyFilter filter = new IdempotencyFilter(new InMemoryIdempotencyStore());
        AtomicInteger handlerInvocations = new AtomicInteger();

        // first request: handler returns 400 (validation failure)
        MockFilterChain badChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws java.io.IOException {
                handlerInvocations.incrementAndGet();
                HttpServletResponse http = (HttpServletResponse) res;
                http.setStatus(400);
                http.setContentType("application/json");
                http.getWriter().write("{\"error\":\"invalid\"}");
            }
        };

        MockHttpServletRequest first = post("key-bad");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(first, firstResponse, badChain);
        assertThat(firstResponse.getStatus()).isEqualTo(400);

        // second request with the SAME key: handler now returns 201 (client fixed its body)
        MockFilterChain goodChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws java.io.IOException {
                handlerInvocations.incrementAndGet();
                HttpServletResponse http = (HttpServletResponse) res;
                http.setStatus(201);
                http.setContentType("application/json");
                http.getWriter().write("{\"id\":\"t-42\"}");
            }
        };

        MockHttpServletRequest second = post("key-bad");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, goodChain);

        // handler must have been called twice (400 was not cached; second request re-executed)
        assertThat(handlerInvocations.get()).isEqualTo(2);
        assertThat(secondResponse.getStatus()).isEqualTo(201);
        assertThat(secondResponse.getContentAsString()).contains("t-42");
    }

    @Test
    void passesThroughWhenNoKey() throws Exception {
        IdempotencyFilter filter = new IdempotencyFilter(new InMemoryIdempotencyStore());
        AtomicInteger invocations = new AtomicInteger();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                invocations.incrementAndGet();
            }
        };
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/orders");
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        assertThat(invocations.get()).isEqualTo(1);
    }

    @Test
    void concurrentFirstRequestsExecuteHandlerOnce() throws Exception {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        IdempotencyFilter filter = new IdempotencyFilter(store);
        AtomicInteger handlerInvocations = new AtomicInteger();

        CountDownLatch start = new CountDownLatch(1);

        java.util.function.Supplier<MockFilterChain> slowChain = () -> new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws java.io.IOException {
                handlerInvocations.incrementAndGet();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                HttpServletResponse http = (HttpServletResponse) res;
                http.setStatus(201);
                http.setContentType("application/json");
                http.getWriter().write("{\"id\":\"t-1\"}");
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        MockHttpServletResponse r1 = new MockHttpServletResponse();
        MockHttpServletResponse r2 = new MockHttpServletResponse();
        try {
            var f1 = pool.submit(() -> {
                start.await();
                filter.doFilter(post("key-conc"), r1, slowChain.get());
                return null;
            });
            var f2 = pool.submit(() -> {
                start.await();
                filter.doFilter(post("key-conc"), r2, slowChain.get());
                return null;
            });
            start.countDown();
            f1.get(5, TimeUnit.SECONDS);
            f2.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // handler executed EXACTLY once; loser got a replayed 201 or a 409, never a second execution
        assertThat(handlerInvocations.get()).isEqualTo(1);
        assertThat(r1.getStatus()).isIn(201, 409);
        assertThat(r2.getStatus()).isIn(201, 409);
    }

    @Test
    void inProgressKeyReturns409() throws Exception {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        IdempotencyFilter filter = new IdempotencyFilter(store);

        // simulate an in-progress (reserved but not yet completed) key
        assertThat(store.reserve("key-inflight")).isTrue();

        AtomicInteger invocations = new AtomicInteger();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                invocations.incrementAndGet();
            }
        };

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(post("key-inflight"), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_CONFLICT);
        assertThat(invocations.get()).isZero(); // handler not executed for an in-progress key
    }

    private static MockHttpServletRequest post(String key) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/orders");
        req.addHeader("Idempotency-Key", key);
        return req;
    }
}
