package com.acme.bank.antifraud;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.bank.contracts.MoneyMapper;
import com.acme.bank.contracts.avro.TransferRequested;
import com.acme.bank.contracts.avro.TransferScreened;
import com.acme.money.Assets;
import com.acme.money.Money;
import com.acme.test.PostgresTestcontainersConfiguration;
import com.acme.test.RedpandaTestcontainersConfiguration;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.redpanda.RedpandaContainer;

@SpringBootTest
@Import({
    PostgresTestcontainersConfiguration.class,
    RedpandaTestcontainersConfiguration.class,
    ScreeningIT.SchemaRegistryProps.class
})
class ScreeningIT {

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
    void duplicateRequestIsScreenedOnce() {
        String bootstrap = redpanda.getBootstrapServers().replaceFirst(".*://", "");
        String srUrl = redpanda.getSchemaRegistryAddress();

        String transferId = "screen-it-dup-1";
        try (Producer<String, TransferRequested> producer = newProducer(bootstrap, srUrl)) {
            var record = new ProducerRecord<>(
                    "transfer-requested",
                    transferId,
                    buildTransferRequested(transferId, Money.of("500.00", Assets.USD)));
            producer.send(record);
            producer.send(record); // redelivery of the same event
            producer.flush();
        }

        // The inbox dedup must apply the screening exactly once despite two deliveries.
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Long decisions = jdbc.queryForObject(
                    "SELECT count(*) FROM screening_decision WHERE transfer_id = ?", Long.class, transferId);
            assertThat(decisions).isEqualTo(1L);
        });
        Long inbox = jdbc.queryForObject(
                "SELECT count(*) FROM processed_messages WHERE listener = 'antifraud' AND message_id = ?",
                Long.class,
                transferId);
        assertThat(inbox).isEqualTo(1L);
    }

    @Test
    void smallAmountIsApproved() {
        String bootstrap = redpanda.getBootstrapServers().replaceFirst(".*://", "");
        String srUrl = redpanda.getSchemaRegistryAddress();

        String transferId = "screen-it-approve-1";
        try (Producer<String, TransferRequested> producer = newProducer(bootstrap, srUrl)) {
            producer.send(new ProducerRecord<>(
                    "transfer-requested",
                    transferId,
                    buildTransferRequested(transferId, Money.of("500.00", Assets.USD))));
            producer.flush();
        }

        try (Consumer<String, TransferScreened> consumer = newConsumer(bootstrap, srUrl, "screening-it-approve")) {
            consumer.subscribe(List.of("transfer-screened"));
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                ConsumerRecords<String, TransferScreened> records = consumer.poll(Duration.ofMillis(500));
                TransferScreened received = null;
                for (ConsumerRecord<String, TransferScreened> r : records) {
                    if (transferId.equals(r.key())) {
                        received = r.value();
                    }
                }
                assertThat(received).isNotNull();
                assertThat(received.getTransferId().toString()).isEqualTo(transferId);
                assertThat(received.getApproved()).isTrue();
            });
        }
    }

    @Test
    void largeAmountIsRejected() {
        String bootstrap = redpanda.getBootstrapServers().replaceFirst(".*://", "");
        String srUrl = redpanda.getSchemaRegistryAddress();

        String transferId = "screen-it-reject-1";
        try (Producer<String, TransferRequested> producer = newProducer(bootstrap, srUrl)) {
            producer.send(new ProducerRecord<>(
                    "transfer-requested",
                    transferId,
                    buildTransferRequested(transferId, Money.of("25000.00", Assets.USD))));
            producer.flush();
        }

        try (Consumer<String, TransferScreened> consumer = newConsumer(bootstrap, srUrl, "screening-it-reject")) {
            consumer.subscribe(List.of("transfer-screened"));
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                ConsumerRecords<String, TransferScreened> records = consumer.poll(Duration.ofMillis(500));
                TransferScreened received = null;
                for (ConsumerRecord<String, TransferScreened> r : records) {
                    if (transferId.equals(r.key())) {
                        received = r.value();
                    }
                }
                assertThat(received).isNotNull();
                assertThat(received.getTransferId().toString()).isEqualTo(transferId);
                assertThat(received.getApproved()).isFalse();
                assertThat(received.getReason().toString()).isEqualTo("AMOUNT_LIMIT");
            });
        }
    }

    private static TransferRequested buildTransferRequested(String transferId, Money amount) {
        return TransferRequested.newBuilder()
                .setTransferId(transferId)
                .setSourceAccountId("acc-src-test")
                .setDestinationAccountId("acc-dst-test")
                .setAmount(MoneyMapper.toAvro(amount))
                .setRequestedBy("test-user")
                .setRequestedAt(Instant.now())
                .build();
    }

    private static Producer<String, TransferRequested> newProducer(String bootstrap, String srUrl) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, srUrl);
        return new KafkaProducer<>(props);
    }

    private static Consumer<String, TransferScreened> newConsumer(String bootstrap, String srUrl, String groupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, srUrl);
        props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
        return new KafkaConsumer<>(props);
    }
}
