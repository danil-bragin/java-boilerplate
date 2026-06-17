package com.acme.demo;

import io.github.resilience4j.retry.annotation.Retry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

/** Demonstrates Resilience4j @Retry: fails the first two calls, then succeeds. */
@Service
public class FlakyService {

    private final AtomicInteger attempts = new AtomicInteger();

    @Retry(name = "flaky")
    public String call() {
        if (attempts.incrementAndGet() < 3) {
            throw new IllegalStateException("transient failure " + attempts.get());
        }
        return "ok";
    }

    public int attempts() {
        return attempts.get();
    }
}
