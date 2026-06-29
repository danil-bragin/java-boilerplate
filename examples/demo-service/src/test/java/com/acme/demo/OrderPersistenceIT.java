package com.acme.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.acme.test.PostgresTestcontainersConfiguration;
import java.time.temporal.ChronoUnit;
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
        // createdAt round-trips, modulo the column's microsecond precision: the in-memory Instant carries
        // nanosecond resolution (Linux clocks especially) but Postgres timestamp(6) stores microseconds (and
        // may round, depending on the driver), so allow ±1µs instead of asserting exact equality across the
        // lossy DB boundary.
        assertThat(found.getCreatedAt()).isCloseTo(saved.getCreatedAt(), within(1, ChronoUnit.MICROS));
    }

    @Test
    void updateBumpsVersionAndAdvancesUpdatedAt() {
        Order saved = orders.saveAndFlush(new Order("SKU-2", 1));

        saved.changeQuantity(9);
        Order updated = orders.saveAndFlush(saved);

        assertThat(updated.getVersion()).isEqualTo(1L);
        assertThat(updated.getQuantity()).isEqualTo(9);
        assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(updated.getCreatedAt());
    }
}
