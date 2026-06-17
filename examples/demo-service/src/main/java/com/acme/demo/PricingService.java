package com.acme.demo;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/** Demonstrates Caffeine caching: repeated lookups for the same sku hit the cache, not the method. */
@Service
public class PricingService {

    private final AtomicInteger computations = new AtomicInteger();

    @Cacheable("prices")
    public int priceFor(String sku) {
        computations.incrementAndGet();
        return sku.length() * 100;
    }

    public int computations() {
        return computations.get();
    }
}
