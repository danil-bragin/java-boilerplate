package com.acme.bank.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.test.PostgresTestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

/**
 * Spec-first drift guard. The contract is the source of truth and is served verbatim from
 * {@code /openapi/bank-api.yaml}; the code-introspected {@code /v3/api-docs} must expose every
 * contract operation. (Missing controller methods are already a compile error because the
 * controller {@code implements TransfersApi} — this test guards the served-docs side.)
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.listener.auto-startup=false")
@Import(PostgresTestcontainersConfiguration.class)
class OpenApiContractTest {

    @LocalServerPort
    int port;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void servedDocsExposeEveryContractOperation() throws Exception {
        RestClient client = RestClient.create();

        String apiDocs = client.get()
                .uri("http://localhost:" + port + "/v3/api-docs")
                .retrieve()
                .body(String.class);

        JsonNode root = mapper.readTree(apiDocs);
        List<String> operationIds = new ArrayList<>();
        root.path("paths")
                .forEach(path -> path.forEach(op -> {
                    JsonNode id = op.path("operationId");
                    if (!id.isMissingNode()) {
                        operationIds.add(id.asText());
                    }
                }));

        assertThat(operationIds).contains("createTransfer", "getTransfer", "listTransfers");
    }

    @Test
    void handWrittenContractIsServed() {
        RestClient client = RestClient.create();

        String yaml = client.get()
                .uri("http://localhost:" + port + "/openapi/bank-api.yaml")
                .retrieve()
                .body(String.class);

        assertThat(yaml).contains("openapi: 3.0.3");
        assertThat(yaml).contains("acme-bank API");
    }
}
