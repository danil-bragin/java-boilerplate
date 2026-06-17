package com.acme.demo;

import static org.assertj.core.api.Assertions.assertThat;

import an.awesome.pipelinr.Pipeline;
import com.acme.test.PostgresTestcontainersConfiguration;
import com.acme.test.RedpandaTestcontainersConfiguration;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.redpanda.RedpandaContainer;

@SpringBootTest
@Import({PostgresTestcontainersConfiguration.class, RedpandaTestcontainersConfiguration.class})
class OutboxExternalizationIT {

    @Autowired
    Pipeline pipeline;

    @Autowired
    RedpandaContainer redpandaContainer;

    @Test
    void creatingAnOrderExternalizesOrderCreatedToKafka() {
        Long id = pipeline.send(new CreateOrderCommand("SKU-OUTBOX", 7));
        assertThat(id).isNotNull();

        // RedpandaContainer.getBootstrapServers() returns "PLAINTEXT://host:port"; strip the prefix.
        String rawBootstrap = redpandaContainer.getBootstrapServers();
        String bootstrap = rawBootstrap.replaceFirst(".*://", "");

        try (Consumer<String, String> consumer = newConsumer(bootstrap)) {
            consumer.subscribe(List.of("orders"));
            Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                boolean found = false;
                for (ConsumerRecord<String, String> r : records) {
                    if (r.value() != null
                            && r.value().contains("\"orderId\":" + id)
                            && r.value().contains("SKU-OUTBOX")) {
                        found = true;
                    }
                }
                assertThat(found)
                        .as("OrderCreated for id %s on topic 'orders'", id)
                        .isTrue();
            });
        }
    }

    private static Consumer<String, String> newConsumer(String bootstrap) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-it");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(props);
    }
}
