package com.acme.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Importable test configuration exposing a Postgres container wired to Spring Boot via
 * {@link ServiceConnection}. Integration tests {@code @Import} this to get a real database
 * with zero datasource configuration. Uses the locally-cached {@code postgres:16-alpine} image.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }
}
