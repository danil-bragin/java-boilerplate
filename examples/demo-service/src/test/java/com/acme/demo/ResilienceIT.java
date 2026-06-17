package com.acme.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.test.PostgresTestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(PostgresTestcontainersConfiguration.class)
class ResilienceIT {

    @Autowired
    FlakyService flaky;

    @Test
    void retryRecoversAfterTransientFailures() {
        String result = flaky.call();
        assertThat(result).isEqualTo("ok");
        assertThat(flaky.attempts()).isEqualTo(3);
    }
}
