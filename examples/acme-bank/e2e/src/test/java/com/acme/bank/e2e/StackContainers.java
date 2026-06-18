package com.acme.bank.e2e;

import java.io.File;
import java.time.Duration;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Brings up the ENTIRE acme-bank stack from {@code compose.bank.yaml} via Testcontainers' local
 * compose runner, building the five service images from their BANK-9 Dockerfiles
 * ({@code withBuild(true)} — the {@code e2eTest} Gradle task depends on each service's bootJar so the
 * jars exist). Tests talk only to the gateway's published HTTP edge and fetch a real token from
 * Keycloak; nothing else is reachable from the test, exactly like a production client.
 *
 * <p>One container per test class (started in {@code @BeforeAll}, stopped in {@code @AfterAll}) keeps
 * the (heavy) startup cost bounded: every scenario in a class shares the same running stack.
 */
final class StackContainers {

    private static final File COMPOSE_FILE = new File("../compose.bank.yaml");

    private static final String GATEWAY = "gateway";
    private static final int GATEWAY_PORT = 8080;
    private static final String KEYCLOAK = "keycloak";
    private static final int KEYCLOAK_PORT = 8080;

    private final ComposeContainer compose;

    StackContainers() {
        this.compose = new ComposeContainer(COMPOSE_FILE)
                .withLocalCompose(true)
                .withBuild(true)
                // Gateway is the public edge — ready once Spring's readiness probe reports UP.
                .withExposedService(
                        GATEWAY,
                        GATEWAY_PORT,
                        Wait.forHttp("/actuator/health/readiness")
                                .forStatusCode(200)
                                .withStartupTimeout(Duration.ofMinutes(5)))
                // Keycloak must have imported the realm before tokens can be minted.
                .withExposedService(
                        KEYCLOAK,
                        KEYCLOAK_PORT,
                        Wait.forHttp("/realms/bank").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(5)))
                .withStartupTimeout(Duration.ofMinutes(6));
    }

    void start() {
        compose.start();
    }

    void stop() {
        compose.stop();
    }

    String gatewayBaseUrl() {
        return "http://" + compose.getServiceHost(GATEWAY, GATEWAY_PORT) + ":"
                + compose.getServicePort(GATEWAY, GATEWAY_PORT);
    }

    String keycloakBaseUrl() {
        return "http://" + compose.getServiceHost(KEYCLOAK, KEYCLOAK_PORT) + ":"
                + compose.getServicePort(KEYCLOAK, KEYCLOAK_PORT);
    }
}
