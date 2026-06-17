package com.acme.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.test.PostgresTestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(PostgresTestcontainersConfiguration.class)
class OrderPersistenceIT {

    @Autowired
    OrderRepository orders;

    @Test
    void persistsAndRetrievesOrderWithAuditingAndVersion() {
        Order saved = orders.save(new Order("SKU-1", 3));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getVersion()).isZero();

        Order found = orders.findById(saved.getId()).orElseThrow();
        assertThat(found.getSku()).isEqualTo("SKU-1");
        assertThat(found.getQuantity()).isEqualTo(3);
        assertThat(found.getCreatedAt()).isEqualTo(saved.getCreatedAt());
    }
}
