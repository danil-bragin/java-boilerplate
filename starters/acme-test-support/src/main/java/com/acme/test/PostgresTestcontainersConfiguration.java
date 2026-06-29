package com.acme.test;

import java.time.Duration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Importable test configuration exposing a Postgres container wired to Spring Boot via
 * {@link DynamicPropertyRegistrar}. Integration tests {@code @Import} this to get a real database
 * with zero datasource configuration. Uses the locally-cached {@code postgres:16-alpine} image.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainersConfiguration {

    @Bean
    PostgreSQLContainer<?> postgresContainer() {
        // Headroom for slow, busy CI runners that start the container many times across a suite (parity with
        // the Redpanda config); fast local machines are unaffected.
        return new PostgreSQLContainer<>("postgres:16-alpine").withStartupTimeout(Duration.ofMinutes(2));
    }

    @Bean
    DynamicPropertyRegistrar postgresProperties(PostgreSQLContainer<?> postgres) {
        return registry -> {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        };
    }
}
