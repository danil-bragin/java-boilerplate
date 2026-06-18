package com.acme.bank.transfers;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.test.PostgresTestcontainersConfiguration;
import com.acme.test.RedpandaTestcontainersConfiguration;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * BANK-15: proves the auto-configured {@code KafkaAdmin} provisions {@code posting-requested} as a
 * MULTI-partition topic from {@link com.acme.bank.transfers.config.SagaTopicsConfig} — the per-account
 * write-parallelism prerequisite (one account → one partition; many accounts spread across partitions).
 */
@SpringBootTest(
        properties = {
            "spring.autoconfigure.exclude=com.acme.security.autoconfigure.SecurityAutoConfiguration",
            "acme.bank.topics.posting-requested.partitions=6"
        })
@Import({
    PostgresTestcontainersConfiguration.class,
    RedpandaTestcontainersConfiguration.class,
    PostingRequestedTopicIT.SchemaRegistryProps.class
})
class PostingRequestedTopicIT {

    @Autowired
    RedpandaContainer redpanda;

    @TestConfiguration
    static class SchemaRegistryProps {
        @Bean
        DynamicPropertyRegistrar schemaRegistry(RedpandaContainer redpanda) {
            return registry -> {
                registry.add(
                        "spring.kafka.producer.properties.schema.registry.url", redpanda::getSchemaRegistryAddress);
                registry.add(
                        "spring.kafka.consumer.properties.schema.registry.url", redpanda::getSchemaRegistryAddress);
            };
        }
    }

    @Test
    void postingRequestedIsMultiPartition() {
        String bootstrap = redpanda.getBootstrapServers().replaceFirst(".*://", "");
        Map<String, Object> props = new HashMap<>();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        try (Admin admin = Admin.create(props)) {
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                Map<String, TopicDescription> described = admin.describeTopics(List.of("posting-requested"))
                        .allTopicNames()
                        .get();
                TopicDescription desc = described.get("posting-requested");
                assertThat(desc).isNotNull();
                assertThat(desc.partitions()).hasSize(6);
            });
        }
    }
}
