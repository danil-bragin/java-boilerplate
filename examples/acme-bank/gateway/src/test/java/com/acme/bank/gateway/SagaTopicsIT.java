package com.acme.bank.gateway;

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
 * BANK-16: proves the auto-configured {@code KafkaAdmin} provisions every saga topic the gateway read-model
 * projection consumes ({@code transfer-requested}, {@code transfer-screened}, {@code transfer-completed},
 * {@code transfer-failed}) as a MULTI-partition topic from {@link com.acme.bank.gateway.config.SagaTopicsConfig}
 * — so the projection never silently subscribes to a 1-partition funnel under
 * {@code allow.auto.create.topics=false}.
 */
@SpringBootTest(properties = "acme.bank.topics.partitions=6")
@Import({
    PostgresTestcontainersConfiguration.class,
    RedpandaTestcontainersConfiguration.class,
    SagaTopicsIT.SchemaRegistryProps.class
})
class SagaTopicsIT {

    @TestConfiguration
    static class SchemaRegistryProps {
        @Bean
        DynamicPropertyRegistrar schemaRegistry(RedpandaContainer redpanda) {
            return registry -> registry.add(
                    "spring.kafka.consumer.properties.schema.registry.url", redpanda::getSchemaRegistryAddress);
        }
    }

    @Autowired
    RedpandaContainer redpanda;

    @Test
    void gatewayProjectionTopicsAreMultiPartition() {
        List<String> topics =
                List.of("transfer-requested", "transfer-screened", "transfer-completed", "transfer-failed");
        String bootstrap = redpanda.getBootstrapServers().replaceFirst(".*://", "");
        Map<String, Object> props = new HashMap<>();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        try (Admin admin = Admin.create(props)) {
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                Map<String, TopicDescription> described =
                        admin.describeTopics(topics).allTopicNames().get();
                for (String topic : topics) {
                    TopicDescription desc = described.get(topic);
                    assertThat(desc).as("topic %s", topic).isNotNull();
                    assertThat(desc.partitions()).as("partitions of %s", topic).hasSize(6);
                }
            });
        }
    }
}
