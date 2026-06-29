package com.acme.test;

import java.time.Duration;
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
        // Default LogMessageWaitStrategy budget (60s) is too tight on small, busy CI runners (2 vCPU) where a
        // suite starts Redpanda many times — readiness can exceed 60s and the wait times out
        // (RetryCountExceededException). Give startup ample headroom; fast local machines still boot in
        // seconds, so this only costs wall-clock on the rare slow start.
        return new RedpandaContainer("redpandadata/redpanda:v24.2.7").withStartupTimeout(Duration.ofMinutes(3));
    }
}
