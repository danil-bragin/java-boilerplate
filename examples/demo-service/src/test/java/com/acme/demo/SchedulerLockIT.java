package com.acme.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.test.PostgresTestcontainersConfiguration;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(PostgresTestcontainersConfiguration.class)
class SchedulerLockIT {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    HeartbeatJob heartbeat;

    @Test
    void scheduledJobRunsAndShedLockRowIsWritten() {
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(heartbeat.runs())
                .isPositive());

        Long lockRows = jdbc.queryForObject("SELECT count(*) FROM shedlock WHERE name = 'heartbeat'", Long.class);
        assertThat(lockRows).isEqualTo(1L);
    }
}
