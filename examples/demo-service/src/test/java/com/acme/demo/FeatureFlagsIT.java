package com.acme.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.test.PostgresTestcontainersConfiguration;
import dev.openfeature.sdk.Client;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(PostgresTestcontainersConfiguration.class)
class FeatureFlagsIT {

    @Autowired
    Client featureClient;

    @Test
    void evaluatesInMemoryFlag() {
        assertThat(featureClient.getBooleanValue("new-checkout", false)).isTrue();
        assertThat(featureClient.getBooleanValue("unknown-flag", false)).isFalse();
    }
}
