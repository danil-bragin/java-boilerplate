package com.acme.bank.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.bank.contracts.avro.TransferCompleted;
import com.acme.bank.contracts.avro.TransferFailed;
import com.acme.test.PostgresTestcontainersConfiguration;
import com.acme.test.RedpandaTestcontainersConfiguration;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.redpanda.RedpandaContainer;

@SpringBootTest
@Import({
    PostgresTestcontainersConfiguration.class,
    RedpandaTestcontainersConfiguration.class,
    NotificationIT.SchemaRegistryProps.class
})
class NotificationIT {

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

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void transferCompletedCreatesNotification() {
        String bootstrap = redpanda.getBootstrapServers().replaceFirst(".*://", "");
        String srUrl = redpanda.getSchemaRegistryAddress();
        String transferId = "notif-it-completed-1";

        try (Producer<String, TransferCompleted> producer = newProducer(bootstrap, srUrl)) {
            producer.send(new ProducerRecord<>(
                    "transfer-completed",
                    transferId,
                    TransferCompleted.newBuilder()
                            .setTransferId(transferId)
                            .setPostingId(transferId)
                            .setCompletedAt(Instant.now())
                            .build()));
            producer.flush();
        }

        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Long count = jdbc.queryForObject(
                    "SELECT count(*) FROM notification WHERE transfer_id = ?", Long.class, transferId);
            assertThat(count).isEqualTo(1L);
        });
    }

    @Test
    void transferFailedCreatesNotification() {
        String bootstrap = redpanda.getBootstrapServers().replaceFirst(".*://", "");
        String srUrl = redpanda.getSchemaRegistryAddress();
        String transferId = "notif-it-failed-1";

        try (Producer<String, TransferFailed> producer = newProducer(bootstrap, srUrl)) {
            producer.send(new ProducerRecord<>(
                    "transfer-failed",
                    transferId,
                    TransferFailed.newBuilder()
                            .setTransferId(transferId)
                            .setReason("AMOUNT_LIMIT")
                            .build()));
            producer.flush();
        }

        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Long count = jdbc.queryForObject(
                    "SELECT count(*) FROM notification WHERE transfer_id = ?", Long.class, transferId);
            assertThat(count).isEqualTo(1L);
        });
    }

    @Test
    void duplicateTransferCompletedIsIdempotent() {
        String bootstrap = redpanda.getBootstrapServers().replaceFirst(".*://", "");
        String srUrl = redpanda.getSchemaRegistryAddress();
        String transferId = "notif-it-dup-1";

        TransferCompleted event = TransferCompleted.newBuilder()
                .setTransferId(transferId)
                .setPostingId(transferId)
                .setCompletedAt(Instant.now())
                .build();

        try (Producer<String, TransferCompleted> producer = newProducer(bootstrap, srUrl)) {
            producer.send(new ProducerRecord<>("transfer-completed", transferId, event));
            producer.send(new ProducerRecord<>("transfer-completed", transferId, event)); // redelivery
            producer.flush();
        }

        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Long inbox = jdbc.queryForObject(
                    "SELECT count(*) FROM processed_messages WHERE listener = 'notifications-completed' AND message_id = ?",
                    Long.class,
                    transferId);
            assertThat(inbox).isEqualTo(1L);
        });

        Long count =
                jdbc.queryForObject("SELECT count(*) FROM notification WHERE transfer_id = ?", Long.class, transferId);
        assertThat(count).isEqualTo(1L);
    }

    private static <V> Producer<String, V> newProducer(String bootstrap, String srUrl) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, srUrl);
        return new KafkaProducer<>(props);
    }
}
