package com.acme.persistence.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.data.auditing.DateTimeProvider;

class ClockDateTimeProviderTest {

    @Test
    void dateTimeProviderUsesInjectedClock() {
        Instant fixed = Instant.parse("2026-06-17T12:00:00Z");
        Clock clock = Clock.fixed(fixed, ZoneOffset.UTC);

        PersistenceAutoConfiguration config = new PersistenceAutoConfiguration();
        DateTimeProvider provider = config.auditingDateTimeProvider(clock);

        TemporalAccessor now = provider.getNow().orElseThrow();
        assertThat(Instant.from(now)).isEqualTo(fixed);
    }
}
