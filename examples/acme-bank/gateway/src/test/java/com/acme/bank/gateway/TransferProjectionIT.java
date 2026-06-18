package com.acme.bank.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.bank.contracts.MoneyMapper;
import com.acme.bank.contracts.avro.TransferCompleted;
import com.acme.bank.contracts.avro.TransferRequested;
import com.acme.money.Assets;
import com.acme.money.Money;
import com.acme.test.PostgresTestcontainersConfiguration;
import com.acme.test.RedpandaTestcontainersConfiguration;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import java.math.BigDecimal;
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
    TransferProjectionIT.SchemaRegistryProps.class
})
class TransferProjectionIT {

    @TestConfiguration
    static class SchemaRegistryProps {
        @Bean
        DynamicPropertyRegistrar schemaRegistry(RedpandaContainer redpanda) {
            return registry -> {
                registry.add(
                        "spring.kafka.consumer.properties.schema.registry.url", redpanda::getSchemaRegistryAddress);
                registry.add(
                        "spring.kafka.producer.properties.schema.registry.url", redpanda::getSchemaRegistryAddress);
            };
        }
    }

    @Autowired
    RedpandaContainer redpanda;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void projectsLatestStatusAndIsRankGuardedAndDeduped() {
        String bootstrap = redpanda.getBootstrapServers().replaceFirst(".*://", "");
        String srUrl = redpanda.getSchemaRegistryAddress();

        String transferId = "proj-it-1";
        Money amount = Money.of("250.00", Assets.USD);

        try (Producer<String, Object> producer = newProducer(bootstrap, srUrl)) {
            // At-least-once delivery: re-send until the projection consumers (which may still be
            // joining their groups) have caught up. The inbox makes redelivery idempotent.
            Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                producer.send(new ProducerRecord<>("transfer-requested", transferId, requested(transferId, amount)));
                producer.flush();
                assertThat(currentStatus(transferId)).isNotNull();
            });

            // Now the REQUESTED row exists; drive it to COMPLETED.
            Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                producer.send(new ProducerRecord<>("transfer-completed", transferId, completed(transferId)));
                producer.flush();
                assertThat(currentStatus(transferId)).isEqualTo("COMPLETED");
            });
        }
        BigDecimal value = jdbc.queryForObject(
                "SELECT amount_value FROM transfer_view WHERE transfer_id = ?", BigDecimal.class, transferId);
        assertThat(value).isEqualByComparingTo("250.00");
        String asset = jdbc.queryForObject(
                "SELECT amount_asset FROM transfer_view WHERE transfer_id = ?", String.class, transferId);
        assertThat(asset).isEqualTo("USD");

        // Redelivery of an older (lower-rank) REQUESTED event must not regress the status.
        try (Producer<String, Object> producer = newProducer(bootstrap, srUrl)) {
            producer.send(new ProducerRecord<>("transfer-requested", transferId, requested(transferId, amount)));
            producer.flush();
        }
        // Give the listener a moment; status must remain COMPLETED (rank guard + inbox dedup).
        Awaitility.await()
                .during(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(currentStatus(transferId)).isEqualTo("COMPLETED"));

        // Exactly one inbox row per (listener, message_id) for the requested listener.
        Long inbox = jdbc.queryForObject(
                "SELECT count(*) FROM processed_messages "
                        + "WHERE listener = 'gateway-projection:transfer-requested' AND message_id = ?",
                Long.class,
                transferId);
        assertThat(inbox).isEqualTo(1L);
    }

    private String currentStatus(String transferId) {
        return jdbc.query(
                "SELECT status FROM transfer_view WHERE transfer_id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                transferId);
    }

    private static TransferRequested requested(String transferId, Money amount) {
        return TransferRequested.newBuilder()
                .setTransferId(transferId)
                .setSourceAccountId("acc-src")
                .setDestinationAccountId("acc-dst")
                .setAmount(MoneyMapper.toAvro(amount))
                .setRequestedBy("test")
                .setRequestedAt(Instant.now())
                .build();
    }

    private static TransferCompleted completed(String transferId) {
        return TransferCompleted.newBuilder()
                .setTransferId(transferId)
                .setPostingId("posting-1")
                .setCompletedAt(Instant.now())
                .build();
    }

    private static Producer<String, Object> newProducer(String bootstrap, String srUrl) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, srUrl);
        return new KafkaProducer<>(props);
    }
}
