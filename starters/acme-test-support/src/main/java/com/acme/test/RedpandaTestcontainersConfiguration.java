package com.acme.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * Importable test configuration exposing a Redpanda (Kafka-compatible) broker wired to Spring Boot
 * via {@link ServiceConnection}. Uses the locally-cached {@code redpandadata/redpanda:v24.2.7} image.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedpandaTestcontainersConfiguration {

    @Bean
    @ServiceConnection
    RedpandaContainer redpandaContainer() {
        return new RedpandaContainer("redpandadata/redpanda:v24.2.7");
    }
}
