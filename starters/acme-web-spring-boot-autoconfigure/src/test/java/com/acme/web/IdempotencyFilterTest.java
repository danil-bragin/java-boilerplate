package com.acme.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletResponse;
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

    private static MockHttpServletRequest post(String key) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/orders");
        req.addHeader("Idempotency-Key", key);
        return req;
    }
}
