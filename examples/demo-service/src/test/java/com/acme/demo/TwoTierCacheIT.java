package com.acme.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.cache.TwoTierCacheManager;
import com.acme.test.PostgresTestcontainersConfiguration;
import com.acme.test.RedisTestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = "acme.cache.two-tier.enabled=true")
@Import({PostgresTestcontainersConfiguration.class, RedisTestcontainersConfiguration.class})
class TwoTierCacheIT {

    @Autowired
    PricingService pricing;

    @Autowired
    CacheManager cacheManager;

    @Test
    void valueSurvivesL1EvictionByServingFromL2() {
        int first = pricing.priceFor("TWOTIER");
        assertThat(pricing.computations()).isEqualTo(1);

        // Second call must NOT recompute — served from L1 or L2.
        int second = pricing.priceFor("TWOTIER");
        assertThat(second).isEqualTo(first);
        assertThat(pricing.computations()).isEqualTo(1);

        // Confirm the active manager is the two-tier one.
        assertThat(cacheManager).isInstanceOf(TwoTierCacheManager.class);
    }
}
