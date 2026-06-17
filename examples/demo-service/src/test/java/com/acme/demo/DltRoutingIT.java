package com.acme.demo;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.TopicBuilder;
import org.testcontainers.redpanda.RedpandaContainer;

@SpringBootTest
@Import({
    PostgresTestcontainersConfiguration.class,
    RedpandaTestcontainersConfiguration.class,
    DltRoutingIT.DltTopicConfig.class
})
class DltRoutingIT {

    /**
     * Pre-creates {@code orders-dlt} via {@link org.springframework.kafka.core.KafkaAdmin} so the
     * {@link org.springframework.kafka.listener.DeadLetterPublishingRecoverer} can publish without a
     * metadata-timeout on the first poison record.
     */
    @TestConfiguration
    static class DltTopicConfig {

        @Bean
        org.apache.kafka.clients.admin.NewTopic ordersDltTopic() {
            return TopicBuilder.name("orders-dlt").partitions(1).replicas(1).build();
        }
    }

    @Autowired
    RedpandaContainer redpandaContainer;

    @Test
    void poisonRecordIsRoutedToDeadLetterTopic() throws Exception {
        // RedpandaContainer.getBootstrapServers() returns "PLAINTEXT://host:port"; strip the prefix.
        String bootstrap = redpandaContainer.getBootstrapServers().replaceFirst(".*://", "");

        try (Producer<String, String> producer = newProducer(bootstrap)) {
            producer.send(new ProducerRecord<>("orders", "poison-key", "this-is-not-valid-json"))
                    .get();
            producer.flush();
        }

        try (Consumer<String, String> consumer = newConsumer(bootstrap)) {
            consumer.subscribe(List.of("orders-dlt"));
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                boolean found = false;
                for (ConsumerRecord<String, String> r : records) {
                    if ("this-is-not-valid-json".equals(r.value())) {
                        found = true;
                    }
                }
                assertThat(found).as("poison record routed to orders-dlt").isTrue();
            });
        }
    }

    private static Producer<String, String> newProducer(String bootstrap) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaProducer<>(props);
    }

    private static Consumer<String, String> newConsumer(String bootstrap) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-it");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(props);
    }
}
