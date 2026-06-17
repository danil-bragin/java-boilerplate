package com.acme.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.test.PostgresTestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(PostgresTestcontainersConfiguration.class)
class CacheIT {

    @Autowired
    PricingService pricing;

    @Test
    void repeatedLookupsAreCached() {
        int first = pricing.priceFor("ABCDE");
        int second = pricing.priceFor("ABCDE");
        assertThat(second).isEqualTo(first);
        assertThat(pricing.computations()).isEqualTo(1);
    }
}
